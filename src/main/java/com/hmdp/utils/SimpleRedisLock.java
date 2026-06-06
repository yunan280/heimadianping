package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {


    //定义锁的名称
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程标识
        String threadId = Thread.currentThread().getId() + "";
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent("Lock:" + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //返回结果
        //
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        //判断锁的线程标识是否与当前线程一致
        String threadId = Thread.currentThread().getId() + "";
        String value = stringRedisTemplate.opsForValue().get("Lock:" + name);
        if (threadId.equals(value)) {
            //一致，删除锁
            stringRedisTemplate.delete("Lock:" + name);
        }
    }
}
