package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.SneakyThrows;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable{
        String queueName = "stream.orders";
        @SneakyThrows
        @Override
        public void run() {
            while(true){

                try {
                    // 1.获取stream队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2. 判断消息获取是否成功
                    if(list==null || list.isEmpty()){
                        //2.1 失败，进入下一次循环
                        continue;
                    }
                    //3. 解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //4. 成功，下单
                    createVoucherOrder(voucherOrder);
                    //5. ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }

            }
        }

        private void handlePendingList() {
            while(true){

                try {
                    // 1.获取pendingList中的订单信息 XREADGROUP GROUP g1 c1  BLOCK 2000 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //2. 判断消息获取是否成功
                    if(list==null || list.isEmpty()){
                        //2.1 失败，进入下一次循环
                        break;
                    }
                    //3. 解析订单中的信息
                    MapRecord<String, Object, Object> record = list.get(0);
                    //Map<Object, Object> values = record.getValue();
                    //VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //5. ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {

                    log.error("处理订单异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }




        //private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
//    private class VoucherOrderHandler implements Runnable{
//        @SneakyThrows
//        @Override
//        public void run() {
//            while(true){
//                // 1.获取队列中的订单信息
//                try {
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    log.error("处理订单异常", e);
//                }
//                // 2.创建订单
//            }
//        }


        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            //用户id
            Long userId = voucherOrder.getUserId();
            //SimpleRedisLock lock = new  SimpleRedisLock("order:"+userId, stringRedisTemplate);
            RLock lock = redissonClient.getLock("order:"+userId);
            boolean isLock = lock.tryLock();
            if(!isLock) {
                log.error("不允许重复下单");
                return;
            }
            try {
                //spring事务失效
                //获取当前对象的代理对象

                proxy.createVoucherOrder(voucherOrder);
            } finally {
                lock.unlock();
            }
        }
    }




    public IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        // 2.判断结果是否为0
        int r = result.intValue();

        // 2.1 非0，无资格
        if(r != 0){
            return  Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        // 2.2 0，有资格,把下单消息保存到阻塞队列

        // 3. 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 4. 返回订单id
        return Result.ok(orderId);
    }

//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        Long userId = UserHolder.getUser().getId();
//        // 1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString()
//        );
//
//        // 2.判断结果是否为0
//        int r = result.intValue();
//
//        // 2.1 非0，无资格
//        if(r != 0){
//            return  Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        // 2.2 0，有资格,把下单消息保存到阻塞队列
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(voucherId);
//
//        // 2.4用户id
//        voucherOrder.setUserId(userId);
//        // 2.5代金券id
//        voucherOrder.setVoucherId(voucherId);
//        // 2.6放入阻塞队列
//        orderTasks.add(voucherOrder);
//
//        // 获取代理对象
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//
//        // 3. 返回订单id
//        return Result.ok(orderId);
//    }
    
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //2.判断秒杀是否开始
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("秒杀尚未开始");
//        }
//        //3.判断秒杀是否结束
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            //未开始
//            return Result.fail("秒杀尚未结束");
//        }
//        //4.判断库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("库存不足");
//        }
//
//        //用户id
//        Long userId = UserHolder.getUser().getId();
//        //SimpleRedisLock lock = new  SimpleRedisLock("order:"+userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:"+userId);
//        boolean isLock = lock.tryLock();
//        if(!isLock) {
//
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            //spring事务失效
//            //获取当前对象的代理对象
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            lock.unlock();
//        }
//
//
//    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        // 5.一人一单
        // 5.1 查询订单
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断是否存在
        if (count > 0) {
            log.error("用户已经购买过一次");
            return;
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)
                .update();
        if (!success) {
            log.error("库存不足") ;
            return;
        }



        //7.创建订单

        save(voucherOrder);


    }
}
