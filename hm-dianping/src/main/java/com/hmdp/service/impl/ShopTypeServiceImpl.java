package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author Larry
 *  
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        // 1.从redis中找商铺缓存
        String typeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        // 2.判断redis中是否有数据
        if(StrUtil.isNotBlank(typeJson)){
            List<ShopType> shopTypes = JSONUtil.toList(typeJson, ShopType.class);
            // 3.找到直接返回
            return Result.ok(shopTypes);
        }
        // 4.redis中无数据，去数据库中查
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 4.1 数据库中不存在
        if(shopTypes == null) {
            return Result.fail("分类不存在！");
        }
        // 4.2 存在，写入redis并返回
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypes)); // 设置了随机过期值，利用不同的TTL解决缓存雪崩问题
        return Result.ok(shopTypes);
    }
}
