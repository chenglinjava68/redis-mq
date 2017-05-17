package com.github.wens.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.Pipeline;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;

/**
 * Created by wens on 2017/3/7.
 */
public class RedisMessageQueue implements Runnable {

    private static Logger log = LoggerFactory.getLogger(RedisMessageQueue.class) ;

    private static String TOPIC_PREFIX = "TOPIC_%s" ;

    private volatile boolean isStart = false ;

    private JedisPool jedisPool ;

    private ConcurrentHashMap<String,ConcurrentHashMap<String,PullMessageWorker>> pullMessageWorkers = new ConcurrentHashMap<String,ConcurrentHashMap<String,PullMessageWorker>>() ;



    public RedisMessageQueue(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public <T> void publish(String topic , byte[] data){
        Jedis jedis = jedisPool.getResource();
        try{
            String script =
                    "local keys=redis.call(\"SMEMBERS\", KEYS[1]);\n" +
                    "if(keys and (table.maxn(keys) > 0)) then\n" +
                    "    for index, key in ipairs(keys) do\n" +
                    "        redis.call(\"RPUSH\", key , ARGV[1]);\n" +
                    "    end\n" +
                    "    redis.call(\"PUBLISH\", KEYS[2] , ARGV[2]);\n" +
                    "end\n" +
                    "return true ;" ;

            jedis.eval(script.getBytes(),
                    Arrays.asList(String.format("_group_:%s" , topic).getBytes() , String.format("_notify_",topic).getBytes()),
                    Arrays.asList(data,topic.getBytes()));

        }finally {
            if(jedis != null ){
                jedis.close();
            }
        }
    }

    public void consume( String topic  , MessageHandler messageHandler ){
        this.consume(topic,"default" ,messageHandler);
    }

    public void consume( String topic , String group , MessageHandler messageHandler ){
        ConcurrentHashMap<String,PullMessageWorker> groupPullMessageWorkerMap  = pullMessageWorkers.get(topic);
        if(groupPullMessageWorkerMap == null ){
            groupPullMessageWorkerMap = new ConcurrentHashMap<String, PullMessageWorker>();
            ConcurrentHashMap<String, PullMessageWorker> oldGroupPullMessageWorkerMap = pullMessageWorkers.putIfAbsent(topic, groupPullMessageWorkerMap);
            if(oldGroupPullMessageWorkerMap != null ){
                groupPullMessageWorkerMap = oldGroupPullMessageWorkerMap ;
            }
            regPullMessageWorker(topic, group, messageHandler, groupPullMessageWorkerMap);
        }else{
            regPullMessageWorker(topic, group, messageHandler, groupPullMessageWorkerMap);
        }

    }

    private void regPullMessageWorker(String topic, String group, MessageHandler messageHandler, ConcurrentHashMap<String, PullMessageWorker> groupPullMessageWorkerMap) {
        PullMessageWorker pullMessageWorker = groupPullMessageWorkerMap.get(group);
        if(pullMessageWorker == null ){
            pullMessageWorker = new PullMessageWorker(topic , group );
            PullMessageWorker oldPullMessageWorker = groupPullMessageWorkerMap.putIfAbsent(group, pullMessageWorker );
            if(oldPullMessageWorker != null ){
                pullMessageWorker = oldPullMessageWorker ;
            }else{
                pullMessageWorker.start();
            }
        }
        pullMessageWorker.addHandler(messageHandler);
    }

    public void start(){
        if(!isStart){
            synchronized (this){
                if(!isStart){
                    isStart = true ;
                    new Thread(this,"redis-mq-sub-thread").start();
                }
            }
        }
    }

    public void close(){
        isStart = false ;
        for(ConcurrentHashMap<String,PullMessageWorker> groupPullMessageWorkerMap : pullMessageWorkers.values() ){
            for(PullMessageWorker pullMessageWorker : groupPullMessageWorkerMap.values() ){
                pullMessageWorker.stop();
            }
        }
        pullMessageWorkers.clear();
        jedisPool.close();
    }


    public void run() {

        while (isStart){

            try{
                Jedis jedis = jedisPool.getResource();
                TopicListener topicListener = new TopicListener();
                try{
                    jedis.subscribe( topicListener  , "_notify_" );
                }finally {
                    if(jedis != null ){
                        jedis.close();
                    }
                }
            }catch (Exception e){
                log.error("Redis has some error : \n {}" , e );
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e1) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    class TopicListener extends JedisPubSub{


        @Override
        public void onMessage(String channel, String message) {
            ConcurrentHashMap<String,PullMessageWorker> groupPullMessageWorkerMap = pullMessageWorkers.get(message);
            if(groupPullMessageWorkerMap != null ){
                for(PullMessageWorker pullMessageWorker : groupPullMessageWorkerMap.values() ){
                    synchronized (pullMessageWorker){
                        pullMessageWorker.notify();
                    }
                }
            }
        }

    }

    class PullMessageWorker implements Runnable {

        private String topic ;

        private String group ;

        private volatile boolean stopped = false ;

        private List<MessageHandler> handlers ;

        public PullMessageWorker(String topic , String group) {
            this.topic = topic ;
            this.group = group ;
            handlers = new CopyOnWriteArrayList<MessageHandler>();
            regGroup();
        }

        private void regGroup() {
            Jedis jedis = jedisPool.getResource();
            try{
                jedis.sadd(String.format("_group_:%s",topic ), String.format("%s:%s",topic,group ));
            }catch (Exception e){
                log.error("Reg group {} fail! \n {}", group , e );
            }finally {
                if(jedis != null ){
                    jedis.close();
                }
            }
        }

        public void addHandler(MessageHandler messageHandler ){
            handlers.add(messageHandler);
        }

        public void run() {

            while(!stopped){

                while(true){
                    try{
                        Jedis jedis = jedisPool.getResource();

                        byte[] data = null ;
                        try{
                            data = jedis.lpop(String.format("%s:%s",topic,group ).getBytes());
                        }catch (Exception e){
                            log.warn("Pull message fail!\n" , e);
                        }finally {
                            if(jedis != null ){
                                jedis.close();
                            }
                        }

                        if(data == null ){
                            synchronized (this){
                                try {
                                    this.wait(20000);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }else{
                            executeHandler(data);
                        }
                    }catch (Exception e){
                        log.error("Pull task fail!",e );
                    }
                }
            }
        }

        private void executeHandler(byte[] data ) {
            for(MessageHandler handler : handlers ){
                try{
                    handler.onMessage(data);
                }catch (Exception e){
                    log.error("Execute handler fail :\n {} " , e );
                }

            }
        }

        public void start(){
            new Thread(this,"redis-mq-pull-" + topic ).start();
        }

        public void stop(){
            stopped = true ;
        }
    }
}
