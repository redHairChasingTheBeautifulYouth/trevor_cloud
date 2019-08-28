package com.trevor.message.core;

public class ListenerKey {

    public static String SPLIT = "_";

    public static String TIME_FIVE = "5";

    public static String TIME_TWO = "2";

    public static String READY = "ready";

    public static String QIANG_ZHAUNG = "qiangZhuang";

    public static String ZHUAN_QUAN = "zhuanQuan";

    public static String XIA_ZHU = "xiaZhu";

    public static String TAI_PAI = "tanPai";

    /**
     *  结算
     */
    public static String SETTLE = "settle";

    public static String getListenerKey(String key ,String roomId ,String time){
        return key + SPLIT + roomId + SPLIT + time;
    }


}