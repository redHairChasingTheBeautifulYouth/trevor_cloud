package com.trevor.message.socket;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.trevor.common.bo.*;
import com.trevor.common.domain.mysql.Room;
import com.trevor.common.domain.mysql.User;
import com.trevor.common.enums.FriendManageEnum;
import com.trevor.common.enums.GameStatusEnum;
import com.trevor.common.enums.RoomTypeEnum;
import com.trevor.common.enums.SpecialEnum;
import com.trevor.common.util.JsonUtil;
import com.trevor.common.util.ObjectUtil;
import com.trevor.message.bo.SocketMessage;
import com.trevor.message.decoder.MessageDecoder;
import com.trevor.message.encoder.MessageEncoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.BoundListOperations;
import org.springframework.data.redis.core.BoundSetOperations;
import org.springframework.data.redis.core.BoundValueOperations;
import org.springframework.stereotype.Component;

import javax.websocket.*;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;


/**
 * 一句话描述该类作用:【牛牛服务端,每次建立链接就新建了一个对象】
 *
 * @author: trevor
 * @create: 2019-03-05 22:29
 **/
@ServerEndpoint(
        value = "/niuniu/{roomId}",
        encoders = {MessageEncoder.class},
        decoders = {MessageDecoder.class}
)
@Component
@Slf4j
public class NiuniuSocket extends BaseServer {

    public Session session;

    public String userId;

    public String roomId;

    /**
     * 连接建立成功调用的方法
     *
     * @param session
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("roomId") String roomId) {
        //roomId合法性检查
        Long roomIdLong = Long.valueOf(roomId);
        Room room = roomService.findOneById(roomIdLong);
        if (room == null) {
            sendMessage(new SocketResult(507));
            close(session);
            return;
        }
        if (!Objects.equals(room.getStatus() ,0)) {
            sendMessage(new SocketResult(506));
            close(session);
            return;
        }
        //token合法性检查
        List<String> params = session.getRequestParameterMap().get(WebKeys.TOKEN);
        if (ObjectUtil.isEmpty(params)) {
            sendMessage(new SocketResult(400));
            close(session);
            return;
        }
        String token = session.getRequestParameterMap().get(WebKeys.TOKEN).get(0);
        User user = userService.getUserByToken(token);
        if (ObjectUtil.isEmpty(user)) {
            sendMessage(new SocketResult(404));
            close(session);
            return;
        }

        SocketResult soc = checkRoom(room ,user);
        if (soc.getHead() != null) {
            sendMessage(soc);
            close(session);
            return;
        }

        this.roomId = roomId;
        this.userId = String.valueOf(user.getId());
        roomSocketService.join(roomId ,this);
        session.setMaxIdleTimeout(1000 * 60 * 45);
        this.session = session;
        stringRedisTemplate.delete(RedisConstant.MESSAGES_QUEUE + userId);

        soc.setHead(1000);
        if (!soc.getIsChiGuaPeople()) {
            BoundSetOperations<String, String> realPlayerOps = stringRedisTemplate.boundSetOps(RedisConstant.REAL_ROOM_PLAYER + roomId);
            realPlayerOps.add(userId);
        }
        soc.setPlayers(roomSocketService.getRealRoomPlayerCount(this.roomId));
        //广播新人加入，前端需要比较useId是否与断线的玩家id（断线重连，断线时会给玩家一个消息谁断线了）、网络不好的玩家是否相等（网络不好重连），不相等则未新加入的玩家
        roomSocketService.broadcast(roomId ,soc);
        //发送房间状态消息
        welcome(roomId);
    }

    /**
     * 接受用户消息
     */
    @OnMessage
    public void onMessage(SocketMessage socketMessage){
        if (Objects.equals(socketMessage.getMessageCode() ,1)) {
            playService.dealReadyMessage(roomId ,this);
        }else if (Objects.equals(socketMessage.getMessageCode() ,2)) {
            playService.dealQiangZhuangMessage(roomId ,this ,socketMessage);
        }else if (Objects.equals(socketMessage.getMessageCode() ,3)) {
            playService.dealXiaZhuMessage(roomId ,this ,socketMessage);
        }else if (Objects.equals(socketMessage.getMessageCode() ,4)) {
            playService.dealTanPaiMessage(roomId ,this ,socketMessage);
        }
    }

    /**
     * 关闭连接调用的方法
     */
    @OnClose
    public void onClose(){
        if (!ObjectUtil.isEmpty(userId)) {
            roomSocketService.leave(roomId ,this);
            //如果是真正的玩家则广播消息
            BoundSetOperations<String, String> realRoomPlayOps = stringRedisTemplate.boundSetOps(RedisConstant.REAL_ROOM_PLAYER + roomId);
            if (realRoomPlayOps.members().contains(userId)) {
                SocketResult res = new SocketResult(1001 ,userId);
                roomSocketService.broadcast(roomId ,res);
            }

        }
    }

    /**
     * 发生错误时调用的方法
     */
    @OnError
    public void onError(Throwable t){
        log.error(t.getMessage() ,t);
    }

    /**
     * 向客户端发消息
     * @param pack
     */
    public void sendMessage(SocketResult pack) {
        BoundListOperations<String, String> messageChannel = stringRedisTemplate.boundListOps(RedisConstant.MESSAGES_QUEUE + userId);
        messageChannel.rightPush(JsonUtil.toJsonString(pack));
    }

    /**
     * 向客户端刷消息
     */
    public void flush(){
        try {
            BoundListOperations<String ,String> ops = stringRedisTemplate.boundListOps(RedisConstant.MESSAGES_QUEUE + userId);
            if (ops != null && ops.size() > 0) {
                List<String> messages = ops.range(0, -1);
                stringRedisTemplate.delete(RedisConstant.MESSAGES_QUEUE + userId);

                StringBuffer stringBuffer = new StringBuffer("[");
                for (String mess : messages) {
                    stringBuffer.append(mess).append(",");
                }
                stringBuffer.setLength(stringBuffer.length() - 1);
                stringBuffer.append("]");
                synchronized (session) {
                    RemoteEndpoint.Async async = session.getAsyncRemote();
                    if (session.isOpen()) {
                        async.sendText(stringBuffer.toString());
                    } else {
                        close(session);
                    }
                }
            }
        }catch (Exception e) {
            log.error(e.getMessage() ,e);
        }
    }

    /**
     * 关闭连接
     *
     * @param session
     */
    public void close(Session session) {
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (IOException e) {
                log.error("close", e.getMessage(), e);
            }
        }
    }


    public void stop(){
        stringRedisTemplate.delete(RedisConstant.MESSAGES_QUEUE + userId);
    }


    private SocketResult checkRoom(Room room ,User user){
        //房主是否开启好友管理功能
        Boolean isFriendManage = Objects.equals(userService.isFriendManage(room.getRoomAuth()) , FriendManageEnum.YES.getCode());
        BoundHashOperations<String, String, String> baseRoomInfoOps = stringRedisTemplate.boundHashOps(RedisConstant.BASE_ROOM_INFO);
        HashSet<Integer> special = JsonUtil.parse(baseRoomInfoOps.get(RedisConstant.SPECIAL), new HashSet<Integer>());
        //开通
        if (isFriendManage) {
            //配置仅限好友
            if (special.contains(SpecialEnum.JUST_FRIENDS.getCode())) {
                Long count = friendManageMapper.countRoomAuthFriendAllow(room.getRoomAuth(), user.getId());
                //不是房主的好友
                if (Objects.equals(count ,0L)) {
                    return new SocketResult(508);
                    //是房主的好友
                }else {
                    return this.dealCanSee(user ,special ,room);
                }
            }
            //未配置仅限好友
            else {
                return this.dealCanSee(user ,special ,room);
            }
            // 未开通
        }else {
            return this.dealCanSee( user ,special ,room);
        }

    }

    /**
     * 处理是否可以观战
     * @throws IOException
     */
    private SocketResult dealCanSee(User user, HashSet<Integer> special ,Room room){
        SocketResult socketResult = new SocketResult();
        socketResult.setUserId(String.valueOf(user.getId()));
        socketResult.setName(user.getAppName());
        socketResult.setPictureUrl(user.getAppPictureUrl());
        BoundSetOperations<String, String> realPlayerOps = stringRedisTemplate.boundSetOps(RedisConstant.REAL_ROOM_PLAYER + room.getId());
        BoundHashOperations<String, String, String> baseRoomInfoOps = stringRedisTemplate.boundHashOps(RedisConstant.BASE_ROOM_INFO + room.getId());
        //允许观战
        if (special!= null && special.contains(SpecialEnum.CAN_SEE.getCode())) {
            if (realPlayerOps.size() < RoomTypeEnum.getRoomNumByType(Integer.valueOf(baseRoomInfoOps.get(RedisConstant.ROOM_TYPE)))) {
                socketResult.setIsChiGuaPeople(Boolean.FALSE);
            }else {
                socketResult.setIsChiGuaPeople(Boolean.TRUE);
            }
            return socketResult;
        //不允许观战
        }else {
            if (realPlayerOps.size() < RoomTypeEnum.getRoomNumByType(Integer.valueOf(baseRoomInfoOps.get(RedisConstant.ROOM_TYPE)))) {
                socketResult.setIsChiGuaPeople(Boolean.FALSE);
                return socketResult;
            }else {
                return new SocketResult(509);
            }

        }
    }

    /**
     * 欢迎玩家加入，发送房间状态信息
     */
    private void welcome(String roomId){
        SocketResult socketResult = new SocketResult();
        socketResult.setHead(2002);
        BoundHashOperations<String, String, String> roomBaseInfoOps = stringRedisTemplate.boundHashOps(RedisConstant.BASE_ROOM_INFO + roomId);
        String gameStatus = roomBaseInfoOps.get(RedisConstant.GAME_STATUS);
        //设置准备的玩家
        if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_READY.getCode()) || Objects.equals(gameStatus ,GameStatusEnum.BEFORE_FAPAI_4.getCode())) {
            socketResult.setReadyPlayerIds(getReadyPlayers());
        }
        //设置玩家先发的4张牌
        else if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_QIANGZHUANG_COUNTDOWN.getCode())) {
            socketResult.setUserPokeMap_4(getPokes_4());
        }
        //设置抢庄的玩家
        else if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_SELECT_ZHUANGJIA.getCode())) {
            socketResult.setUserPokeMap_4(getPokes_4());
            socketResult.setQiangZhuangMap(getQiangZhuangPlayers());
        }
        //设置庄家
        else if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_XIANJIA_XIAZHU.getCode())) {
            socketResult.setUserPokeMap_4(getPokes_4());
            //socketResult.setQiangZhuangMap(getQiangZhuangPlayers());
            socketResult.setZhuangJiaUserId(getZhuangJia());
        }
        //设置闲家下注
        else if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_LAST_POKE.getCode())) {
            socketResult.setUserPokeMap_4(getPokes_4());
            //socketResult.setQiangZhuangMap(getQiangZhuangPlayers());
            socketResult.setZhuangJiaUserId(getZhuangJia());
            socketResult.setXianJiaXiaZhuMap(getXianJiaXiaZhu());
        }
        //设置玩家发的最后一张牌
        else if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_TABPAI_COUNTDOWN.getCode())) {
            socketResult.setUserPokeMap_4(getPokes_4());
            socketResult.setZhuangJiaUserId(getZhuangJia());
            socketResult.setUserPokeMap_1(getLastPoke(Boolean.FALSE ,Boolean.FALSE));
        }
        //设置谁摊牌了
        else if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_CALRESULT.getCode())) {
            socketResult.setUserPokeMap_4(getPokes_4());
            socketResult.setZhuangJiaUserId(getZhuangJia());
            socketResult.setTanPaiPlayerUserIds(getTanPaiPlayer());
            socketResult.setUserPokeMap_1(getLastPoke(Boolean.TRUE ,Boolean.FALSE));
        }
        //设置本局结果
        else if (Objects.equals(gameStatus ,GameStatusEnum.BEFORE_RETURN_RESULT.getCode())) {
            socketResult.setUserPokeMap_4(getPokes_4());
            socketResult.setZhuangJiaUserId(getZhuangJia());
            socketResult.setUserPokeMap_1(getLastPoke(null ,Boolean.TRUE));
            setResult(socketResult);
        }
        sendMessage(socketResult);
        return;
    }

    /**
     * 得到准备的玩家
     * @return
     */
    private Set<String> getReadyPlayers(){
        BoundSetOperations<String, String> readyPlayersOps = stringRedisTemplate.boundSetOps(RedisConstant.READY_PLAYER + roomId);
        if (readyPlayersOps != null && readyPlayersOps.size() > 0) {
            return readyPlayersOps.members();
        }
        return null;
    }

    /**
     * 得到先发的4张牌
     * @return
     */
    private Map<String ,List<String>> getPokes_4(){
        BoundHashOperations<String, String, String> pokesOps = stringRedisTemplate.boundHashOps(RedisConstant.POKES + roomId);
        Map<String ,List<String>> userPokeMap_4 = Maps.newHashMap();
        Map<String, String> userPokeStrMap = pokesOps.entries();
        for (Map.Entry<String ,String> entry : userPokeStrMap.entrySet()) {
            userPokeMap_4.put(entry.getKey() ,JsonUtil.parse(entry.getValue() ,new ArrayList<String>()).subList(0 ,4));
        }
        return userPokeMap_4;
    }

    /**
     * 得到抢庄的玩家
     * @return
     */
    private Map<String ,String> getQiangZhuangPlayers(){
        BoundHashOperations<String, String ,String> qiangZhuangOps = stringRedisTemplate.boundHashOps(RedisConstant.QIANGZHAUNG + roomId);
        if (qiangZhuangOps != null || qiangZhuangOps.size() > 0) {
            return qiangZhuangOps.entries();
        }
        return null;
    }

    /**
     * 得到庄家
     * @return
     */
    private String getZhuangJia(){
        BoundValueOperations<String, String> zhuangJiaOps = stringRedisTemplate.boundValueOps(RedisConstant.ZHUANGJIA + roomId);
        return zhuangJiaOps.get();
    }

    /**
     * 得到闲家下注
     * @return
     */
    private Map<String ,String> getXianJiaXiaZhu(){
        BoundHashOperations<String, String, String> xianJiaXiaZhuOps = stringRedisTemplate.boundHashOps(RedisConstant.XIANJIA_XIAZHU + roomId);
        if (xianJiaXiaZhuOps != null && xianJiaXiaZhuOps.size() > 0) {
            return xianJiaXiaZhuOps.entries();
        }
        return null;
    }

    /**
     * 得到最后一张牌
     * @return
     */
    private Map<String ,List<String>> getLastPoke(Boolean isTanPai ,Boolean isReturnResult){
        Map<String ,List<String>> userPokeMap_1 = new HashMap<>();
        BoundHashOperations<String, String, String> pokesOps = stringRedisTemplate.boundHashOps(RedisConstant.POKES + roomId);
        BoundSetOperations<String, String> tanPaiOps = stringRedisTemplate.boundSetOps(RedisConstant.TANPAI + roomId);
        Map<String, String> userPokeStrMap = pokesOps.entries();
        Set<String> tanPaiPlayerIds = tanPaiOps.members();
        if (isReturnResult) {
            for (Map.Entry<String ,String> entry : userPokeStrMap.entrySet()) {
                if (!tanPaiPlayerIds.contains(entry.getKey())) {
                    userPokeMap_1.put(entry.getKey() ,JsonUtil.parse(entry.getValue() ,new ArrayList<String>()).subList(4 ,5));
                }
            }
        }else {
            for (Map.Entry<String ,String> entry : userPokeStrMap.entrySet()) {
                if (isTanPai) {
                    if (tanPaiPlayerIds.contains(entry.getKey())) {
                        userPokeMap_1.put(entry.getKey() ,JsonUtil.parse(entry.getValue() ,new ArrayList<String>()).subList(4 ,5));
                    }
                }else {
                    if (Objects.equals(entry.getKey() ,userId)) {
                        userPokeMap_1.put(entry.getKey() ,JsonUtil.parse(entry.getValue() ,new ArrayList<String>()).subList(4 ,5));
                        break;
                    }
                }
            }
        }
        return userPokeMap_1;
    }

    /**
     * 得到摊牌的玩家
     * @return
     */
    private Set<String> getTanPaiPlayer(){
        BoundSetOperations<String, String> tanPaiOps = stringRedisTemplate.boundSetOps(RedisConstant.TANPAI + roomId);
        return tanPaiOps.members();
    }

    /**
     * 得到结果
     * @return
     */
    private void setResult(SocketResult socketResult){
        //设置本局得分
        BoundHashOperations<String, String, String> scoreOps = stringRedisTemplate.boundHashOps(RedisConstant.SCORE + roomId);
        Map<String ,String> scoreMap = scoreOps.entries();
        Map<String ,Integer> stringIntegerMap = Maps.newHashMap();
        for (Map.Entry<String ,String> entry : scoreMap.entrySet()) {
            stringIntegerMap.put(entry.getKey() ,Integer.valueOf(entry.getValue()));
        }
        socketResult.setScoreMap(stringIntegerMap);

        BoundHashOperations<String, String, String> paiXingOps = stringRedisTemplate.boundHashOps(RedisConstant.PAI_XING + roomId);
        Map<String ,String> paiXingMap = paiXingOps.entries();
        Map<String ,Integer> paiXingIntegerMap = Maps.newHashMap();
        for (Map.Entry<String ,String> entry : paiXingMap.entrySet()) {
            paiXingIntegerMap.put(entry.getKey() ,JsonUtil.parse(entry.getValue() ,new PaiXing()).getPaixing());
        }
        socketResult.setPaiXing(paiXingIntegerMap);
    }
}
