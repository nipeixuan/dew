package group.idealworld.dew.core.cluster.spi.rocket;

import com.ecfront.dew.common.exception.RTUnsupportedEncodingException;
import group.idealworld.dew.core.cluster.AbsClusterMQ;
import group.idealworld.dew.core.cluster.dto.MessageWrap;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.apache.rocketmq.remoting.common.RemotingHelper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class RocketClusterMQ extends AbsClusterMQ {

    private static SendBeforeFun sendBeforeFun = (topic, messageProperties) -> null;
    private static SendErrorFun sendErrorFun = (ex, beforeResult) -> {
    };
    private static SendFinishFun sendFinishFun = beforeResult -> {
    };
    private static ReceiveBeforeFun receiveBeforeFun = (topic, messageProperties) -> null;
    private static ReceiveErrorFun receiveErrorFun = (ex, beforeResult) -> {
    };
    private static ReceiveFinishFun receiveFinishFun = beforeResult -> {
    };

    private RocketAdapter rocketAdapter;

    private String nameServer;

    private String groupName;

    public RocketClusterMQ(RocketAdapter rocketAdapter, String nameServer, String groupName) {
        this.rocketAdapter = rocketAdapter;
        this.nameServer = nameServer;
        this.groupName = groupName;
    }

    /**
     * Sets send before fun.
     *
     * @param sendBeforeFun the send before fun
     */
    public static void setSendBeforeFun(SendBeforeFun sendBeforeFun) {
        RocketClusterMQ.sendBeforeFun = sendBeforeFun;
    }

    /**
     * Sets send error fun.
     *
     * @param sendErrorFun the send error fun
     */
    public static void setSendErrorFun(SendErrorFun sendErrorFun) {
        RocketClusterMQ.sendErrorFun = sendErrorFun;
    }

    /**
     * Sets send finish fun.
     *
     * @param sendFinishFun the send finish fun
     */
    public static void setSendFinishFun(SendFinishFun sendFinishFun) {
        RocketClusterMQ.sendFinishFun = sendFinishFun;
    }

    /**
     * Sets receive before fun.
     *
     * @param receiveBeforeFun the receive before fun
     */
    public static void setReceiveBeforeFun(ReceiveBeforeFun receiveBeforeFun) {
        RocketClusterMQ.receiveBeforeFun = receiveBeforeFun;
    }

    /**
     * Sets receive error fun.
     *
     * @param receiveErrorFun the receive error fun
     */
    public static void setReceiveErrorFun(ReceiveErrorFun receiveErrorFun) {
        RocketClusterMQ.receiveErrorFun = receiveErrorFun;
    }

    /**
     * Sets receive finish fun.
     *
     * @param receiveFinishFun the receive finish fun
     */
    public static void setReceiveFinishFun(ReceiveFinishFun receiveFinishFun) {
        RocketClusterMQ.receiveFinishFun = receiveFinishFun;
    }

    @Override
    protected boolean doPublish(String topic, String message, Optional<Map<String, Object>> header, boolean confirm) {
        RocketMQTemplate rocketMQTemplate = rocketAdapter.getRocketMQTemplate();
        Object funResult = null;
        if (confirm) {
            throw new RTUnsupportedEncodingException("Rocket doesn't support confirm mode");
        }
        try {
            Map<String, Object> sendHeader = getMQHeader(topic);
            header.ifPresent(sendHeader::putAll);
            Message<?> msg = MessageBuilder.withPayload(message).copyHeaders(sendHeader)
                    .build();
            funResult = sendBeforeFun.invoke(topic, sendHeader);
            rocketMQTemplate.syncSend(topic, msg);

        } catch (Exception e) {
            logger.error("[MQ] Rocket publish error.", e);
            sendErrorFun.invoke(e, funResult);
            return false;
        }
        sendFinishFun.invoke(funResult);
        return true;
    }

    @Override
    protected void doSubscribe(String topic, Consumer<MessageWrap> consumer) {
        DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(groupName);
        mqConsumer.setNamesrvAddr(nameServer);
        mqConsumer.setInstanceName(UUID.randomUUID().toString());

        try {
            mqConsumer.subscribe(topic, "*");
            mqConsumer.setMessageModel(MessageModel.BROADCASTING);
            mqConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            receiveMessage(topic, mqConsumer, consumer);
            mqConsumer.start();
        } catch (MQClientException e) {
            logger.error("[MQ] Rocket response error.", e);
        }
    }

    @Override
    protected boolean doRequest(String address, String message, Optional<Map<String, Object>> header, boolean confirm) {
        RocketMQTemplate rocketMQTemplate = rocketAdapter.getRocketMQTemplate();
        Object funResult = null;
        if (confirm) {
            throw new RTUnsupportedEncodingException("Rocket doesn't support confirm mode");
        }
        try {
            Map<String, Object> sendHeader = getMQHeader(address);
            header.ifPresent(sendHeader::putAll);
            funResult = sendBeforeFun.invoke(address, sendHeader);
            Message<?> msg = MessageBuilder.withPayload(message).copyHeaders(sendHeader)
                    .build();
            rocketMQTemplate.syncSend(address, msg);
        } catch (Exception e) {
            logger.error("[MQ] Rocket publish error.", e);
            sendErrorFun.invoke(e, funResult);
            return false;
        }
        sendFinishFun.invoke(funResult);
        return true;
    }

    @Override
    protected void doResponse(String address, Consumer<MessageWrap> consumer) {
        DefaultMQPushConsumer mqConsumer = new DefaultMQPushConsumer(groupName);
        mqConsumer.setNamesrvAddr(nameServer);
        mqConsumer.setInstanceName(UUID.randomUUID().toString());
        try {
            mqConsumer.subscribe(address, "*");
            mqConsumer.setMessageModel(MessageModel.CLUSTERING);
            mqConsumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
            receiveMessage(address, mqConsumer, consumer);
            mqConsumer.start();
        } catch (MQClientException e) {
            logger.error("[MQ] Rocket response error.", e);
        }
    }

    private void receiveMessage(String topic, DefaultMQPushConsumer mqConsumer, Consumer<MessageWrap> consumer){
        mqConsumer.registerMessageListener((MessageListenerConcurrently) (list, context) -> {
            AtomicReference<Object> funResult = null;
            try {
                list.parallelStream().forEach((messageExt)->{
                    Map<String, Object> headers = messageExt.getProperties()
                            .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                    Map<String, Object> receiveHeader = setMQHeader(topic, headers);
                    funResult.set(receiveBeforeFun.invoke(topic, receiveHeader));
                    consumer.accept(new MessageWrap(topic, Optional.of(receiveHeader), Arrays.toString(messageExt.getBody())));
                });
            } catch (Exception e) {
                receiveErrorFun.invoke(e, funResult);
                logger.error("[MQ] Rocket response error.", e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
            receiveFinishFun.invoke(funResult);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
    }

    @Override
    public boolean supportHeader() {
        return true;
    }
}