package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_LIST_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
       //先从redis中查询商铺类型信息
        String shopTypeListJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_LIST_KEY);
        //判断是否存在
        if (shopTypeListJson != null) {
            //存在，直接返回,把数据从redis中反序列化为list并返回
            return Result.ok(JSONUtil.toList(shopTypeListJson, ShopType.class));

        }
        //不存在，根据id查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();

        //将查出来的结果序列化为JSON字符串并写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_LIST_KEY, JSONUtil.toJsonStr(shopTypeList),30, TimeUnit.MINUTES);

        return Result.ok(shopTypeList);
    }
}
