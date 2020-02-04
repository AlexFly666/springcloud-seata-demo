package com.fly.demo.service;

import com.fly.demo.entity.Order;
import java.math.BigDecimal;

/**
 * @author 王延飞
 */
public interface OrderService {

    /**
     * 创建订单
     * @param order
     * @return
     */
    void create(Order order);

    /**
     * 修改订单状态
     * @param userId
     * @param money
     * @param status
     */
    void update(Long userId,BigDecimal money,Integer status);
}
