package kr.tx24.wx.api.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import kr.tx24.lib.lang.CommonUtils;
import kr.tx24.lib.lang.SystemUtils;
import kr.tx24.lib.map.LinkedMap;
import kr.tx24.lib.mapper.JacksonUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ExtCode {

    private static final Path CODE_JSON   = Paths.get(SystemUtils.getConfigDirectory(),"code.json");

    private static final String DELIMITER = ":";
    private static final String BANK      = "BANK";
    private static final String KTFC      = "KTFC";
    private static final String FCS       = "FCS";
    private static final String PISP      = "PISP";
    private static final String DEFAULT_MESSAGE = "미정의,";
    private static final String NET_ERROR_CODE = "X000";
    private static final String NET_ERROR_MESSAGE = "통신장애";

    private static LinkedMap<String,String> CODE_MAP = null;



    static {
        LinkedMap<String, LinkedMap<String, String>> rawMap =
                JacksonUtils.get().fromJson(
                        CODE_JSON,
                        new TypeReference<LinkedMap<String, LinkedMap<String, String>>>() {});

        if (rawMap == null || rawMap.isEmpty()) {
            CODE_MAP = new LinkedMap<String,String>(1);
        }else {

            int size = 0;
            for (Map<String, String> mMap : rawMap.values()) {
                size += mMap.size();
            }

            LinkedMap<String, String> tempMap = new LinkedMap<>(size + (size >> 2));

            for (Map.Entry<String, LinkedMap<String, String>> categoryEntry : rawMap.entrySet()) {

                String prefix = categoryEntry.getKey() + DELIMITER;

                for (Map.Entry<String, String> entry : categoryEntry.getValue().entrySet()) {
                    tempMap.put(prefix + entry.getKey(), entry.getValue());   // intern 제거
                }
            }

            CODE_MAP = tempMap;
        }
    }

    public static String getBankMessage(String bankCode,String code){

        Object obj = CODE_MAP.get(BANK+DELIMITER+CommonUtils.nToB(bankCode)+DELIMITER+code);
        if(obj == null){
            return DEFAULT_MESSAGE+CommonUtils.nToB(bankCode);
        }else{
            return CommonUtils.toString(obj);
        }
    }

    public static String getKTFC(String code){
        return getCodeMessage(KTFC, code);
    }

    public static String getFCS(String code){
        return getCodeMessage(FCS, code);
    }

    public static String getPISP(String code) {
        return getCodeMessage(PISP, code);
    }

    private static String getCodeMessage(String category, String code) {
        if (code == null || NET_ERROR_CODE.equals(code)) {
            return NET_ERROR_MESSAGE;
        }

        Object obj = CODE_MAP.get(category + DELIMITER + code);
        if(obj == null){
            return DEFAULT_MESSAGE + code;
        }else{
            return CommonUtils.toString(obj);
        }

    }



}
