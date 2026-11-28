package kr.tx24.wx.api.hyphen;


import kr.tx24.wx.api.utils.ExtCode;
import kr.tx24.wx.api.WalletPolicy;
import kr.tx24.lib.inter.INet;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.lang.DateUtils;
import kr.tx24.lib.map.LinkedMap;
import kr.tx24.lib.map.MapFactory;
import kr.tx24.lib.map.TypeRegistry;
import kr.tx24.lib.mapper.JacksonUtils;
import kr.tx24.lib.redis.RedisUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.io.*;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class HyphenPisp {

    private static final Logger logger = LoggerFactory.getLogger(HyphenPisp.class);

    private static final ScheduledThreadPoolExecutor SOCKET_TIMEOUTS = socketTimeouts();

    private static ScheduledThreadPoolExecutor socketTimeouts() {

        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "wx-hyphen-timeout");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static final Charset HYPHEN_CHARSET      = Charset.forName("KSC5601");
    private static final String HYPHEN_REDIS_SEQ	= "WX:HYPHEN:";    //WX 는 모계좌별로 분리
    private static final int HYPHEN_SEQUENCE_LIMIT  = 950000;

    private static final String HYPHEN_FRIM_SVC_CODE= "6000";
    private static final String HYPHEN_FCS_SVC_CODE	= "5000";

    private static final String HYPHEN_PUBLIC_TEST  = "121.138.30.31";  //하이픈 개발
    private static final String HYPHEN_PUBLIC_PROD  = "121.138.30.10";  //하이픈 운영
    //private static final String HYPHEN_PUBLIC_PROD  = "203.245.13.112"; 더페이원 경유


    private static final int HYPHEN_PORT_PROD       = 19237;
    private static final int HYPHEN_PORT_TEST       = 19238;
    private static final int FRAME_LENGTH           = 300;
    private static final int HYPHEN_HEAD_LENGTH     = 300;

    private static final int HYPHEN_CONNECT_TIMEOUT = 3000;
    private static final int HYPHEN_READ_TIMEOUT    = 15000;

    private static final String[] PISP_COMPANYS     = {"TX","OPL"};


    private static final SecureRandom RANDOM = new SecureRandom();


    private String host         = "";
    private int port            = 0;
    private INet inet           = null;


    public HyphenPisp(){
    }


    private void setEnv(boolean development){
        if(development){
            this.host       = HYPHEN_PUBLIC_TEST;
            this.port       = HYPHEN_PORT_TEST;
        }else{
            this.host       = HYPHEN_PUBLIC_PROD;
            this.port       = HYPHEN_PORT_PROD;
        }
    }

    /**
     * 계좌 예금주 조회 일반
     * certType : "" : 예금주명 조회
     * certType : "ACCOUNT_RRN"    , rrn 필수 예금주 조회 + 계좌 신원 확인
     * certType : "ACCOUNT_HOLDER" , holder, rrn 필수 예금주명 + 신원확인번호
     * @param inet
     * @return
     */
    public LinkedMap<String,Object> holder(INet inet){
        this.inet = inet;
        //inet.head.development true
        setEnv(inet.head().getBoolean("development"));



        LocalDateTime requestTime = DateUtils.getDate();
        KRWHead head = new KRWHead()
            .trCd("")
            .company(inet.data().getString("acqSid"))
            .bankCd("099")            //FCS 시 고정값
            .trxType("0600")
            .trxCd("400")
            .cnt("1")
            .seqNo(getSequence())
            .trxDay(DateUtils.toString(requestTime.toLocalDate()))
            .trxTime(DateUtils.toString(requestTime.toLocalTime()))
            .rootTrxDay("") // 조회일자
            .rootSeqNo("")    // 원거래번호
            .filler("");


        KRW0600400 data = new KRW0600400()
            .transactionDay(DateUtils.toString(requestTime.toLocalDate(), "MMdd"))
            .recvBankCd(inet.data().getString("recvBankCd"))
            .recvAccount(inet.data().getString("recvAccount"));


        if(inet.data().isEmpty("recvBankCd")){
            return getErrorMessage("은행코드가 누락되었습니다. recvBankCd");
        }

        if(inet.data().isEmpty("recvAccount")){
            return getErrorMessage("계좌번호가 누락되었습니다. recvAccount");
        }


        //계좌번호 + 신원확인번호 99 : ACCOUNT_RRN
        //예금주명 + 신원확인번호 77 : ACCOUNT_HOLDER
        if(inet.data().isEquals("certType","ACCOUNT_RRN")){
            logger.info("예금주 조회 + 계좌 신원 확인");
            data.socialCheck("99");
            data.socialNumber(inet.data().getString("rrn"));

            if(inet.data().isEmpty("rrn")){
                return getErrorMessage("신원확인번호가 누락되었습니다. ACCOUNT_RRN rrn ");
            }

        }else if(inet.data().isEquals("certType","ACCOUNT_HOLDER")){
            logger.info("예금주 확인 + 계좌 신원 확인 + 실명 인증 ");
            data.socialCheck("77");
            data.socialNumber(inet.data().getString("rrn"));
            data.holder(inet.data().getString("holder"));

            if(inet.data().isEmpty("rrn")){
                return getErrorMessage("신원확인번호가 누락되었습니다. ACCOUNT_HOLDER rrn ");
            }
            if(inet.data().isEmpty("holder")){
                return getErrorMessage("신원확인번호가 누락되었습니다. ACCOUNT_RRN holder  ");
            }

        }else{
            logger.info("예금주 조회");
        }




        logger.info("[{}]", JacksonUtils.get().toJson(head));


        byte[] request = head.get(data.get());
        logger.info("예금주 조회 전문 길이: {}", request.length);
        byte[] response = execute(request, WalletPolicy.HOLDER_TIMEOUT_MILLIS);


        KRWHead resHead = new KRWHead(response);
        KRW0600400 resData = new KRW0600400(response);

        //예금주명을 확인하여 리턴
        LinkedMap<String,Object> resultMap = getMessage(resHead);
        resultMap.put("holder", resData.getHolder());

        return resultMap;
    }


    /**
     * 지급 이체
     * @param inet
     * @return
     */
    public LinkedMap<String,Object> transfer(INet inet){
        this.inet = inet;
        //inet.head.development true
        setEnv(inet.head().getBoolean("development"));



        LocalDateTime requestTime = DateUtils.getDate();
        KRWHead head = new KRWHead()
                .trCd("")
                .company(inet.data().getString("acqSid"))
                .bankCd(inet.data().getString("acqBankCd"))            //FCS 시 고정값
                .trxType("0100")
                .trxCd("100")
                .cnt("1")
                .seqNo(getSequence())
                .trxDay(DateUtils.toString(requestTime.toLocalDate()))
                .trxTime(DateUtils.toString(requestTime.toLocalTime()))
                .rootTrxDay("") // 조회일자
                .rootSeqNo("")    // 원거래번호
                .filler("");


        KRW0100100 data = new KRW0100100()
                .masterAccount(inet.data().getString("acqAccount"))
                .sign(inet.data().getString("sign"))
                .amount(inet.data().getString("amount"))
                .recvBankCd(inet.data().getString("recvBankCd"))
                .recvAccount(inet.data().getString("recvAccount"))
                .trasferTime(head.getTrxTime())
                .sender(inet.data().getString("sender"))
                .description(inet.data().getString("description"),head.getBankCd());

        if(inet.data().isEmpty("acqBankCd")){
            return getErrorMessage("모계좌은행코드 누락 acqBankCd ");
        }

        if(inet.data().isEmpty("acqAccount")){
            return getErrorMessage("모계좌번호 누락 acqAccount ");
        }

        if(inet.data().getLong("amount") == 0){
            return getErrorMessage("이체금액 누락 amount ");
        }

        if(inet.data().isEmpty("recvBankCd")){
            return getErrorMessage("수신 은행코드 누락 recvBankCd ");
        }

        if(inet.data().isEmpty("recvAccount")){
            return getErrorMessage("수신 계좌번호 누락 recvAccount ");
        }


        byte[] request = head.get(data.get());
        byte[] response = execute(request, WalletPolicy.TRANSFER_TIMEOUT_MILLIS);

        KRWHead resHead = new KRWHead(response);
        KRW0100100 resData = new KRW0100100(response);

        //예금주명을 확인하여 리턴
        LinkedMap<String,Object> resultMap = getMessage(resHead);
        resultMap.put("balance" , CommonUtils.parseLong(resData.getBalanceSign()+resData.getBalanceAmount()));
        resultMap.put("fee"     , CommonUtils.parseLong(resData.getFee()));

        return resultMap;
    }


    /**
     * 지급 이체 결과 조회
     * @param inet
     * @return
     */
    public LinkedMap<String,Object> inquery(INet inet){
        this.inet = inet;
        //inet.head.development true
        setEnv(inet.head().getBoolean("development"));

        String rootSpecNumber = CommonUtils.length(inet.data().getString("acqTrxId")) > 11
                ? inet.data().getString("acqTrxId").substring(6) : "";

        LocalDateTime requestTime = DateUtils.getDate();
        KRWHead head = new KRWHead()
                .trCd("")
                .company(inet.data().getString("acqSid"))
                .bankCd(inet.data().getString("acqBankCd"))            //FCS 시 고정값
                .trxType("0600")
                .trxCd("101")
                .cnt("1")
                .seqNo(getSequence())
                .trxDay(DateUtils.toString(requestTime.toLocalDate()))
                .trxTime(DateUtils.toString(requestTime.toLocalTime()))
                .rootTrxDay("") // 조회일자
                .rootSeqNo("")    // 원거래번호
                .filler("");


        KRW0600101 data = new KRW0600101()
                .rootSpecNumber(rootSpecNumber);

        if(inet.data().isEmpty("acqBankCd")){
            return getErrorMessage("모계좌은행코드 누락 acqBankCd ");
        }


        if(inet.data().isEmpty("acqTrxId")){
            return getErrorMessage("원거래 거래번호 누락 acqTrxId ");
        }


        byte[] request = head.get(data.get());
        byte[] response = execute(request);

        KRWHead resHead = new KRWHead(response);
        KRW0600101 resData = new KRW0600101(response);

        //예금주명을 확인하여 리턴
        LinkedMap<String,Object> resultMap = getMessage(resHead);
        resultMap.put("masterAccount"   , resData.getMasterAccount());
        resultMap.put("recvAccount"     , resData.getRecvAccount());
        resultMap.put("recvBankCd"      , resData.getRecvBankCd());
        resultMap.put("amount"          , resData.getAmount());
        resultMap.put("transferTime"    , resData.getTransferTime());
        resultMap.put("fee"             , resData.getFee());
        resultMap.put("resultCd"        , resData.getResultCd());
        if(resData.getResultCd().length() == 3) {
            resultMap.put("resultMsg", ExtCode.getKTFC(resData.getResultCd()));
        }else{
            if(CommonUtils.startsWith(resHead.getCompany(),PISP_COMPANYS)) {   //PISP 이니셜로 시작하는 것은
                resultMap.put("resultMsg", ExtCode.getPISP(resHead.getAcqResultCd()));
            }else{
                resultMap.put("resultMsg", ExtCode.getBankMessage(resHead.getBankCd(),resHead.getAcqResultCd()));
            }
        }

        logger.info("result : {},{},{}", resultMap.getString("acqTrxId"), resultMap.getString("acqResultCd"), resultMap.getString("acqResultMsg"));
        logger.info("result : {},{},{}", resultMap.getString("acqTrxId"), resultMap.getString("resultCd"), resultMap.getString("resultMsg"));
        return resultMap;
    }


    /**
     * 모계좌 잔액조회
     * @param inet
     * @return
     */
    public LinkedMap<String,Object> balance(INet inet){
        this.inet = inet;
        //inet.head.development true
        setEnv(inet.head().getBoolean("development"));

        LocalDateTime requestTime = DateUtils.getDate();
        KRWHead head = new KRWHead()
                .trCd("")
                .company(inet.data().getString("acqSid"))
                .bankCd(inet.data().getString("acqBankCd"))            //FCS 시 고정값
                .trxType("0600")
                .trxCd("300")
                .cnt("1")
                .seqNo(getSequence())
                .trxDay(DateUtils.toString(requestTime.toLocalDate()))
                .trxTime(DateUtils.toString(requestTime.toLocalTime()))
                .rootTrxDay("") // 조회일자
                .rootSeqNo("")    // 원거래번호
                .filler("");


        KRW0600300 data = new KRW0600300()
                .masterAccount(inet.data().getString("acqAccount"));

        if(inet.data().isEmpty("acqBankCd")){
            return getErrorMessage("모계좌은행코드 누락 acqBankCd ");
        }

        if(inet.data().isEmpty("acqAccount")){
            return getErrorMessage("모계좌번호 누락 acqAccount ");
        }


        byte[] request = head.get(data.get());
        byte[] response = execute(request);

        KRWHead resHead = new KRWHead(response);
        KRW0600300 resData = new KRW0600300(response);

        //예금주명을 확인하여 리턴
        LinkedMap<String,Object> resultMap = getMessage(resHead);
        String current = resData.getCurrentAmount().trim();
        String sign = resData.getSign().trim();
        if (!current.matches("[0-9]{1,13}") || !sign.matches("[+-]?")) return resultMap;
        resultMap.put("balance"         , Long.parseLong(sign + current));
        resultMap.put("availableBalance", CommonUtils.parseLong(resData.getSign()+resData.getPayAmount()));

        return resultMap;
    }


    /**
     * 집계
     * @param inet
     * @return
     */
    public LinkedMap<String,Object> summary(INet inet){
        this.inet = inet;
        //inet.head.development true
        setEnv(inet.head().getBoolean("development"));

        LocalDateTime requestTime = DateUtils.getDate();
        KRWHead head = new KRWHead()
                .trCd("")
                .company(inet.data().getString("acqSid"))
                .bankCd(inet.data().getString("acqBankCd"))            //FCS 시 고정값
                .trxType("0700")
                .trxCd("100")
                .cnt("1")
                .seqNo(getSequence())
                .trxDay(DateUtils.toString(requestTime.toLocalDate()))
                .trxTime(DateUtils.toString(requestTime.toLocalTime()))
                .rootTrxDay(inet.data().getString("inqueryDay")) // 조회일자
                .rootSeqNo("")    // 원거래번호
                .filler("");


        KRW0700100 data = new KRW0700100()
                .masterAccount(inet.data().getString("acqAccount"));


        if(inet.data().isEmpty("inqueryDay")){
            return getErrorMessage("조회일자 누락 inqueryDay");
        }

        if(inet.data().isEmpty("acqBankCd")){
            return getErrorMessage("모계좌은행코드 누락 acqBankCd ");
        }

        if(inet.data().isEmpty("acqAccount")){
            return getErrorMessage("모계좌번호 누락 acqAccount ");
        }


        byte[] request = head.get(data.get());
        logger.info("[{}]", new String(request));
        byte[] response = execute(request);

        KRWHead resHead = new KRWHead(response);
        KRW0700100 resData = new KRW0700100(response);

        //예금주명을 확인하여 리턴
        LinkedMap<String,Object> resultMap = getMessage(resHead);
        resultMap.put("reqCount"         , CommonUtils.parseLong(resData.getReqCount()));
        resultMap.put("reqAmount"        , CommonUtils.parseLong(resData.getReqAmount()));
        resultMap.put("successCount"     , CommonUtils.parseLong(resData.getSuccessCount()));
        resultMap.put("successAmount"    , CommonUtils.parseLong(resData.getSuccessAmount()));
        resultMap.put("failCount"        , CommonUtils.parseLong(resData.getFailCount()));
        resultMap.put("failAmount"       , CommonUtils.parseLong(resData.getFailAmount()));
        resultMap.put("fee"              , CommonUtils.parseLong(resData.getFee()));
        resultMap.put("otherReqCount"         , CommonUtils.parseLong(resData.getOtherReqCount()));
        resultMap.put("otherReqAmount"        , CommonUtils.parseLong(resData.getOtherReqAmount()));
        resultMap.put("otherSuccessCount"     , CommonUtils.parseLong(resData.getOtherSuccessCount()));
        resultMap.put("otherSuccessAmount"    , CommonUtils.parseLong(resData.getOtherSuccessAmount()));
        resultMap.put("otherFailCount"        , CommonUtils.parseLong(resData.getOtherFailCount()));
        resultMap.put("otherFailAmount"       , CommonUtils.parseLong(resData.getOtherFailAmount()));
        resultMap.put("otherFee"              , CommonUtils.parseLong(resData.getOtherFee()));

        return resultMap;
    }



    public byte[] execute(byte[] request) {

        return execute(request, 0);
    }

    private byte[] execute(byte[] request, int timeoutMillis) {

        long start = System.nanoTime();

        byte[] response = null;
        String phase = "INIT";
        ScheduledFuture<?> timeout = null;

        // UUID 대신 SecureRandom 사용
        byte[] key = new byte[16];
        RANDOM.nextBytes(key);

        try (Socket socket = new Socket()) {

            if (timeoutMillis > 0) {
                long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - start);
                timeout = SOCKET_TIMEOUTS.schedule(() -> {
                    try {
                        socket.close();
                    } catch (IOException failure) {
                        logger.debug("기관 통신 제한시간 도달 후 소켓 종료 실패", failure);
                    }
                }, remaining, TimeUnit.NANOSECONDS);
            }

            logger.info("hyphen send length : {}", request.length);

            socket.setTcpNoDelay(true);
            socket.setSoTimeout(timeoutMillis > 0 ? timeoutMillis : HYPHEN_READ_TIMEOUT);

            phase = "CONNECT";
            socket.connect(new InetSocketAddress(host, port), HYPHEN_CONNECT_TIMEOUT);
            byte[] encrypted = encrypt(key, request);
            phase = "WRITE";
            OutputStream out = socket.getOutputStream();
            out.write(encrypted);
            out.flush();

            phase = "READ_HEADER";
            InputStream in = socket.getInputStream();
            // Header(4Byte)
            byte[] header = read(in, 4);
            // Binary Int Length
            int length = CommonUtils.parseInt(CommonUtils.toString(header,HYPHEN_CHARSET));

            if (length <= 0 || length > 1024 * 1024) {
                throw new IOException("Invalid packet length : " + length);
            }
            phase = "READ_BODY";
            byte[] body = read(in, length);

            phase = "DECRYPT";
            response = decrypt(key, body);
            if (timeoutMillis > 0 && System.nanoTime() - start >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) {
                response = null;
                throw new SocketTimeoutException("기관 통신 제한시간 초과");
            }

        }catch (SocketTimeoutException e) {
            logger.info("{},{}",host,port);
            logger.error("Hyphen 통신 시간 초과 ({}) - 연결 또는 응답 대기 시간이 제한을 초과했습니다.", phase, e);
        }catch (ConnectException e) {
            logger.error("Hyphen 서버 연결 실패 ({}) - 서버에 연결할 수 없습니다. 주소={}, 포트={}", phase, host, port, e);

        }catch (EOFException e) {
            logger.error("Hyphen 서버 연결 종료 ({}) - 서버가 응답 데이터를 모두 전송하기 전에 연결을 종료했습니다.", phase, e);

        }catch (IOException e) {
            logger.error("Hyphen 입출력 오류 ({}) - 네트워크 통신 중 오류가 발생했습니다.", phase, e);

        }catch (Exception e) {
            logger.error("Hyphen 처리 중 예기치 않은 오류 발생 (단계: {})", phase, e);
        } finally {
            if (timeout != null) timeout.cancel(false);
        }

        if (response == null) {
            response = request.clone();
            System.arraycopy(
                    "X000X000".getBytes(HYPHEN_CHARSET),0,response,47,8);
        }

        logger.info("hyphen recv length : {}", response.length);

        logger.info("hyphen elapsed : {} ms",
                String.format("%.3f",
                        (System.nanoTime() - start) / 1_000_000d));
        return response;
    }

    private static byte[] read(InputStream in, int length) throws IOException {

        byte[] data = new byte[length];
        int pos = 0;
        while (pos < length) {
            int n = in.read(data, pos, length - pos);
            if (n < 0) {
                throw new EOFException("Connection closed. expected=" + length + ", received=" + pos);
            }
            pos += n;
        }
        return data;
    }







    private void close(AutoCloseable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (Exception e) {
            }
        }
    }


    private byte[] encrypt(byte[] key , byte[] request){
        byte[] buf = null;
        byte[] init = null;
        if(CommonUtils.toString(request,9,3).equals("FCS")){
            logger.info("encrypt : {},{}",HYPHEN_FCS_SVC_CODE,new String(request,9,3));
            init = (HYPHEN_FCS_SVC_CODE+inet.data().getString("acqKey2")).getBytes();

        }else{
            logger.info("encrypt : {}",HYPHEN_FRIM_SVC_CODE);
            init = (HYPHEN_FRIM_SVC_CODE+inet.data().getString("acqKey2")).getBytes();
        }
        buf = new byte[init.length+request.length];
        System.arraycopy(init,0, buf, 0, init.length);
        System.arraycopy(request,0, buf, init.length, request.length);
        return hyphen_encrypt(key,buf);

    }


    private byte[] decrypt(byte[] key, byte[] buf){
        try{
            return ks_seed_decrypt(key,buf);
        }catch(Exception e){
            logger.error("hyphen_decrypt error : {}",e.getMessage());
            return null;
        }
    }

    private byte[] hyphen_encrypt(byte[] p_kbuf, byte[] p_dbuf){
        try{
            byte[] e_kbuf	= ks_rsa_encrypt(p_kbuf);

            byte[] e_dbuf	= ks_seed_encrypt(p_kbuf, p_dbuf);
            byte[] e_sbuf	= new byte[4+1+e_kbuf.length+4+e_dbuf.length];

            int midx = 0;
            System.arraycopy("01292".getBytes(), 0	,e_sbuf	,midx,  5); midx+= 5;
            System.arraycopy(e_kbuf, 0 , e_sbuf,midx, e_kbuf.length); midx+=e_kbuf.length	;

            System.arraycopy(("0000".substring(String.valueOf(e_dbuf.length).length(),4) + e_dbuf.length).getBytes()	,0	,e_sbuf	,midx,  4			); midx+= 4				;
            System.arraycopy(e_dbuf, 0, e_sbuf,midx, e_dbuf.length); midx+=e_dbuf.length	;

            return e_sbuf;
        }catch(Exception e){
            logger.error("kscms_encrypt error : {}",e.getMessage());
        }

        return null;
    }


    private byte[] ks_rsa_encrypt(byte[] sbuf) throws java.security.NoSuchProviderException,javax.crypto.NoSuchPaddingException,javax.crypto.BadPaddingException,java.security.NoSuchAlgorithmException,java.security.spec.InvalidKeySpecException,java.security.InvalidKeyException,javax.crypto.IllegalBlockSizeException{
        BigInteger modulus			= new BigInteger("d4846c2b8228dddfab9e614da2a324c1cc7b29d848cc005624d3a09667a2aab9073290bace6aa536ddceb3c47ddda78d9954da06c83aa65b939c5ec773a3787e71bec5a1c077bb446c06b393d2537967645d386b4b0b4ec21372fdc728c56693028c1c3915c1c4279793eb3dccefd6bf49b86cc7d88a47b0d44aba9e73750fcd",16);
        BigInteger publicExponent	= new BigInteger("0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010001",16);

        RSAPublicKeySpec pubKeySpec = new RSAPublicKeySpec(modulus, publicExponent);

        KeyFactory keyfactory = KeyFactory.getInstance("RSA");
        java.security.PublicKey publickey = keyfactory.generatePublic(pubKeySpec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");

        cipher.init(Cipher.ENCRYPT_MODE, publickey);

        return cipher.doFinal(sbuf);

    }


    public byte[] ks_seed_encrypt(byte[] kbuf, byte[] mbuf) throws java.security.NoSuchProviderException,javax.crypto.BadPaddingException,javax.crypto.NoSuchPaddingException,java.security.InvalidAlgorithmParameterException,java.security.NoSuchAlgorithmException,java.security.InvalidKeyException,javax.crypto.IllegalBlockSizeException{
        return new  HyphenSeed(kbuf).cbc_encrypt(mbuf) ;
    }




    private byte[] ks_seed_decrypt(byte[] kbuf, byte[] mbuf) throws java.security.NoSuchProviderException,javax.crypto.NoSuchPaddingException,java.security.InvalidAlgorithmParameterException,javax.crypto.BadPaddingException,java.security.NoSuchAlgorithmException,java.security.InvalidKeyException,javax.crypto.IllegalBlockSizeException{
        return new  HyphenSeed(kbuf).cbc_decrypt(mbuf) ;
    }



    private String getSequence() {
        String hqId = inet == null ? "" : inet.data().getString("hqId");
        if(hqId == null || !hqId.matches("[A-Za-z0-9_-]{1,24}")) {
            throw new IllegalStateException("기관 일련번호를 나눌 모계좌 ID 가 없습니다.");
        }
        String key = HYPHEN_REDIS_SEQ + hqId;
        // Redis 증가는 한 번만 실행한다. 실제 카운터를 되감으면 경계에서 같은 번호를 두 번 발급할 수 있다.
        long counter = RedisUtils.incr(key);
        if (counter < 1) throw new IllegalStateException("기관 일련번호를 발급하지 못했습니다.");
        long sequence = (counter - 1) % (HYPHEN_SEQUENCE_LIMIT - 1) + 1;
        return String.format(java.util.Locale.ROOT, "%06d", sequence);
    }

    private LinkedMap<String,Object> getMessage(KRWHead resHead){
        LinkedMap<String,Object> resultMap = MapFactory.createObjectMap(TypeRegistry.MAP_LINKEDMAP_OBJECT);

        resultMap.put("acqResultCd"     , resHead.getAcqResultCd());
        if(resHead.getCompany().startsWith("FCS")) {
            resultMap.put("acqResultMsg", ExtCode.getFCS(resHead.getAcqResultCd()));
        }else if(CommonUtils.startsWith(resHead.getCompany(),PISP_COMPANYS)) {   //PISP 이니셜로 시작하는 것은
            resultMap.put("acqResultMsg", ExtCode.getPISP(resHead.getAcqResultCd()));
        }else{
            resultMap.put("acqResultMsg", ExtCode.getBankMessage(resHead.getBankCd(),resHead.getAcqResultCd()));
        }
        resultMap.put("acqTrxId"        , resHead.getTrxDay().substring(2)+resHead.getSeqNo());
        resultMap.put("acqTrxDay"       , resHead.getTrxDay());
        resultMap.put("acqTrxTime"      , resHead.getTrxTime());
        resultMap.put("acqTrxDate"      , resHead.getTrxDay()+resHead.getTrxTime());

        logger.info("result : {},{},{}", resHead.getSeqNo(), resultMap.getString("acqResultCd"), resultMap.getString("acqResultMsg"));

        return resultMap;
    }


    private LinkedMap<String,Object> getErrorMessage(String message){
        LinkedMap<String,Object> resultMap = MapFactory.createObjectMap(TypeRegistry.MAP_LINKEDMAP_OBJECT,7);

        resultMap.put("acqResultCd"     , "X002");
        resultMap.put("acqResultMsg"    , "필수값없음,"+message);

        LocalDateTime responseTime = DateUtils.getDate();
        resultMap.put("acqTrxDay"       , DateUtils.toString(responseTime.toLocalDate()));
        resultMap.put("acqTrxTime"      , DateUtils.toString(responseTime.toLocalTime()));
        resultMap.put("acqTrxDate"      , resultMap.getString("acqTrxDay")+resultMap.getString("acqTrxTime"));
        resultMap.put("acqTrxId"        , resultMap.getString("acqTrxDate").substring(2));

        return resultMap;
    }







}
