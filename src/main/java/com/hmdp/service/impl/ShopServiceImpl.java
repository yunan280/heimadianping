package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {

        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //用互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //用逻辑过期解决缓存击穿
        Shop shop = queryWithLogicalExpire(id);
        if (shop==null){
            return Result.fail("店铺不存在");
        }

        //返回
        return Result.ok(shop);
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = java.util.concurrent.Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        //从redis 中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //判断是否命中空值（缓存穿透标记）
        if ("".equals(shopJson)) {
            return null;
        }
        //判断是否存在，不存在则查数据库并预热到Redis
        if (StrUtil.isBlank(shopJson)) {
            Shop shop = getById(id);
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            try {
                saveShop2Redis(id, 20L);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            return shop;
        }
        //命中，需要把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return shop;
        }
        //过期，需要缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if (isLock) {
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        //返回过期的店铺信息
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        //从reids 中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY   + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的是否是空值
        if (shopJson != null) {
            //命中的是空值，返回店铺不存在
            return null;
        }

        //实现缓存重建

        //获取互斥锁
        String lock = null;
        Shop shop = null;
        try {
            lock = RedisConstants.LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lock);

            //判断是否获取锁成功
            if (!isLock) {
                //失败，休眠并重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                return queryWithMutex(id);

            }
            //获取锁成功，根据id查询数据库
            shop = getById(id);
            //不存在，根据id查询数据库
            if(shop==null){
                //将空值写入redis并返回，防止缓存穿透
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //存在，写入redis并返回商铺信息
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }finally{
            unLock(lock);
        }

        return shop;
    }


    public Shop queryWithPassThrough(Long id) {
        //从reids 中查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY   + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        //判断命中的是否是空值
        if (shopJson != null) {
            //命中的是空值，返回店铺不存在
            return null;
        }

        Shop shop = getById(id);
        //不存在，根据id查询数据库
        if(shop==null){
            //将空值写入redis并返回，防止缓存穿透
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        //存在，写入redis并返回商铺信息
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+ id, JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    //尝试获取锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    //释放锁,进行删除
    private void unLock(String key) {
        stringRedisTemplate.delete(key);
    }

    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = getById(id);
        Thread.sleep(200);
        //封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData),CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }

    @Override
    @Transactional//事务,来控制缓存重建和数据库更新的原子性
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("店铺id不能为空");//缺少id
        }
        //更新数据库
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
