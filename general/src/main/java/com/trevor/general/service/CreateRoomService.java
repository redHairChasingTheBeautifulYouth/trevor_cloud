package com.trevor.general.service;

import com.alibaba.fastjson.JSON;
import com.trevor.commom.bo.JsonEntity;
import com.trevor.commom.bo.RedisConstant;
import com.trevor.commom.bo.ResponseHelper;
import com.trevor.commom.dao.mongo.NiuniuRoomParamMapper;
import com.trevor.commom.dao.mysql.CardConsumRecordMapper;
import com.trevor.commom.dao.mysql.PersonalCardMapper;
import com.trevor.commom.dao.mysql.RoomMapper;
import com.trevor.commom.domain.mongo.NiuniuRoomParam;
import com.trevor.commom.domain.mysql.CardConsumRecord;
import com.trevor.commom.domain.mysql.Room;
import com.trevor.commom.domain.mysql.User;
import com.trevor.commom.enums.ConsumCardEnum;
import com.trevor.commom.enums.MessageCodeEnum;
import com.trevor.general.BizException;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Objects;

/**
 * @author trevor
 * @date 2019/3/8 16:53
 */
@Service
public class CreateRoomService{

    @Resource
    private RoomMapper roomMapper;

    @Resource
    private PersonalCardMapper personalCardMapper;

    @Resource
    private CardConsumRecordMapper cardConsumRecordMapper;

    @Resource
    private StringRedisTemplate redisTemplate;

    @Resource
    private NiuniuRoomParamMapper niuniuRoomParamMapper;


    /**
     * 创建一个房间,返回主键,将房间放入Map中
     * @param niuniuRoomParam
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public JsonEntity<Long> createRoom(NiuniuRoomParam niuniuRoomParam , User user) {
        checkParm(niuniuRoomParam);
        //判断玩家拥有的房卡数量是否超过消耗的房卡数
        Integer cardNumByUserId = personalCardMapper.findCardNumByUserId(user.getId());
        Integer consumCardNum;
        if (Objects.equals(niuniuRoomParam.getConsumCardNum() , ConsumCardEnum.GAME_NUM_12_CARD_3.getCode())) {
            consumCardNum = ConsumCardEnum.GAME_NUM_12_CARD_3.getConsumCardNum();
            if (cardNumByUserId < ConsumCardEnum.GAME_NUM_12_CARD_3.getConsumCardNum()) {
                return ResponseHelper.withErrorInstance(MessageCodeEnum.USER_ROOMCARD_NOT_ENOUGH);
            }
        }else {
            consumCardNum = ConsumCardEnum.GAME_NUM_24_CARD_6.getConsumCardNum();
            if (cardNumByUserId < ConsumCardEnum.GAME_NUM_24_CARD_6.getConsumCardNum()) {
                return ResponseHelper.withErrorInstance(MessageCodeEnum.USER_ROOMCARD_NOT_ENOUGH);
            }
        }
        //生成房间，将房间信息存入数据库
        Integer totalNum = 0;
        if (Objects.equals(consumCardNum ,ConsumCardEnum.GAME_NUM_12_CARD_3.getConsumCardNum())) {
            totalNum = 12;
        }else {
            totalNum = 24;
        }
        Long currentTime = System.currentTimeMillis();
        Room room = new Room();
        room.generateRoomBase(niuniuRoomParam.getRoomType() ,user.getId() ,currentTime ,totalNum);
        roomMapper.insertOne(room);

        //插入mongoDB
        niuniuRoomParamMapper.save(niuniuRoomParam);

        //存入redis
        BoundHashOperations<String, String, String> ops = redisTemplate.boundHashOps(RedisConstant.BASE_ROOM_INFO + room.getId());
        ops.put(RedisConstant.ROOM_TYPE ,String.valueOf(niuniuRoomParam.getRoomType()));
        ops.put(RedisConstant.ROB_ZHUANG_TYPE ,String.valueOf(niuniuRoomParam.getRobZhuangType()));
        ops.put(RedisConstant.BASE_POINT ,String.valueOf(niuniuRoomParam.getBasePoint()));
        ops.put(RedisConstant.RULE ,String.valueOf(niuniuRoomParam.getRule()));
        ops.put(RedisConstant.XIAZHU ,String.valueOf(niuniuRoomParam.getXiazhu()));
        ops.put(RedisConstant.SPECIAL , JSON.toJSONString(niuniuRoomParam.getSpecial()));
        ops.put(RedisConstant.PAIXING ,JSON.toJSONString(niuniuRoomParam.getPaiXing()));

        ops.put(RedisConstant.GAME_STATUS ,"1");
        ops.put(RedisConstant.RUNING_NUM ,"0");
        ops.put(RedisConstant.TOTAL_NUM ,totalNum.toString());

        //生成房卡消费记录
        CardConsumRecord cardConsumRecord = new CardConsumRecord();
        cardConsumRecord.generateCardConsumRecordBase(room.getId() , user.getId() ,consumCardNum);
        cardConsumRecordMapper.insertOne(cardConsumRecord);

        //更新玩家的房卡数量信息
        personalCardMapper.updatePersonalCardNum(user.getId() ,cardNumByUserId - consumCardNum);
        return ResponseHelper.createInstance(room.getId() , MessageCodeEnum.CREATE_SUCCESS);
    }

    private void checkParm(NiuniuRoomParam niuniuRoomParameter){
        Integer roomType = niuniuRoomParameter.getRoomType();
        if (!Objects.equals(roomType ,1) && !Objects.equals(roomType ,2) && !Objects.equals(roomType ,3)) {
            throw new BizException(-200 ,"roomType 错误");
        }
        Integer robZhuangType = niuniuRoomParameter.getRobZhuangType();
        if (!Objects.equals(robZhuangType ,1) && !Objects.equals(robZhuangType ,2) &&
                !Objects.equals(robZhuangType ,3) && !Objects.equals(robZhuangType ,4)) {
            throw new BizException(-200 ,"robZhuangType 错误");
        }
        Integer basePoint = niuniuRoomParameter.getBasePoint();

    }


}
