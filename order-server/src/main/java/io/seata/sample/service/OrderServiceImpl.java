package io.seata.sample.service;

import io.seata.sample.dao.OrderDao;
import io.seata.sample.entity.Order;
import io.seata.sample.feign.AccountApi;
import io.seata.sample.feign.StorageApi;
import io.seata.spring.annotation.GlobalTransactional;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * @Title: 订单业务实现类
 * @ClassName: io.seata.sample.service.OrderServiceImpl.java
 * @Description: 创建订单->调用库存服务扣减库存->调用账户服务扣减账户余额->修改订单状态
 *
 * @Copyright 2016-2019 谐云科技 - Powered By 研发中心
 * @author: 王延飞
 * @date:  2020/2/4 20:20
 * @version V1.0
 */
@Service("orderServiceImpl")
public class OrderServiceImpl implements OrderService{

    private static final Logger LOGGER = LoggerFactory.getLogger(OrderServiceImpl.class);

    @Autowired
    private OrderDao orderDao;
    @Autowired
    private StorageApi storageApi;
    @Autowired
    private AccountApi accountApi;

    /**
     * 创建订单
     * @param order
     * @return
     * 测试结果：
     * 1.添加本地事务：仅仅扣减库存
     * 2.不添加本地事务：创建订单，扣减库存
     */
    @Override
    @GlobalTransactional(name = "fsp-create-order",rollbackFor = Exception.class)
    public void create(Order order) {
        LOGGER.info("------->交易开始");
        //本地方法
        orderDao.create(order);

        //远程方法 扣减库存
        storageApi.decrease(order.getProductId(),order.getCount());

        //远程方法 扣减账户余额

        LOGGER.info("------->扣减账户开始order中");
        accountApi.decrease(order.getUserId(),order.getMoney());
        LOGGER.info("------->扣减账户结束order中");

        LOGGER.info("------->交易结束");
    }

    /**
     * 修改订单状态
     */
    @Override
    public void update(Long userId,BigDecimal money,Integer status) {
        LOGGER.info("修改订单状态，入参为：userId={},money={},status={}",userId,money,status);
        orderDao.update(userId,money,status);
    }
}
