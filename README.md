# springcloud整合seata实现分布式事务
### 概览
##### 操作步骤如下：
- 1.seata-server端，修改server配置
- 2.client端（你自己的项目），引入配置文件，修改配置文件
- 3.数据源代理设置
- 4.创建数据库表
- 5.启动注册中心，启动server,启动client（包括订单服务，库存服务、商品服务）

##### 关于调用成环和seata-server HA，见最后部分

### 1.此demo技术选型及版本信息

- 注册中心：eureka 2.1.2

- 服务间调用：feign 2.1.2

- 持久层：mybatis  3.5.0

- 数据库：mysql 5.7.20

- Springboot:2.1.8.RELEASE(Greenwich.SR2)

- Springcloud:2.1.2

- jdk:1.8 

- seata:1.0 (最新稳定版)


### 2.demo概况
demo分为四个项目，单独启动。

- eureka: 注册中心（http://localhost:8761/eureka/）
- order: 订单服务，用户下单后，会创建一个订单添加在order数据库，同时会扣减库存storage，扣减账户account;
- storage: 库存服务，用户扣减库存；
- account: 账户服务，用于扣减账户余额；

order服务关键代码如下：
```java
    @Override
    @GlobalTransactional(name = "fsp-create-order",rollbackFor = Exception.class) //此注解开启全局事务
    public void create(Order order) {
        //本地方法 创建订单
        orderDao.create(order);
        //远程方法 扣减库存
        storageApi.decrease(order.getProductId(),order.getCount());
        //远程方法 扣减账户余额  可在accountServiceImpl中模拟异常
        accountApi.decrease(order.getUserId(),order.getMoney());
    }
```
### 3.使用步骤 建表语句

### 创建业务数据库

- seata_order：存储订单的数据库；
- seata_storage：存储库存的数据库；
- seata_account：存储账户信息的数据库
- seata_server:  如果使用db模式存储事务日志，需要我们要创建三张表：global_table，branch_table，lock_table
- 所以在每个业务库中，还要创建undo_log表，建表sql在/conf/db_undo_log.sql中。

### 4.seata server端配置信息修改
seata-server中，/conf目录下，有两个配置文件,需要结合自己的情况来修改：

#### 4.1.单个seata server端配置信息修改

##### 1.file.conf 

原始内容

```java
service {
  #transaction service group mapping
  vgroup_mapping.my_test_tx_group = "default"
  #only support when registry.type=file, please don't set multiple addresses
  default.grouplist = "127.0.0.1:8091"
  #disable seata
  disableGlobalTransaction = false
}

## transaction log store, only used in seata-server
store {
  ## store mode: file、db
  mode = "file"

  ## file store property
  file {
    ## store location dir
    dir = "sessionStore"
  }

  ## database store property
  db {
    ## the implement of javax.sql.DataSource, such as DruidDataSource(druid)/BasicDataSource(dbcp) etc.
    datasource = "dbcp"
    ## mysql/oracle/h2/oceanbase etc.
    db-type = "mysql"
    driver-class-name = "com.mysql.jdbc.Driver"
    url = "jdbc:mysql://127.0.0.1:3306/seata"
    user = "mysql"
    password = "mysql"
  }
}
```

里面有事务组配置，锁配置，事务日志存储等相关配置信息，由于此demo使用db存储事务信息，我们这里要修改store中的配置：
```java
## transaction log store
store {
  ## store mode: file、db
  mode = "db"   修改这里，表明事务信息用db存储

  ## file store 当mode=db时，此部分配置就不生效了，这是mode=file的配置
  file {
    dir = "sessionStore"

    # branch session size , if exceeded first try compress lockkey, still exceeded throws exceptions
    max-branch-session-size = 16384
    # globe session size , if exceeded throws exceptions
    max-global-session-size = 512
    # file buffer size , if exceeded allocate new buffer
    file-write-buffer-cache-size = 16384
    # when recover batch read size
    session.reload.read_size = 100
    # async, sync
    flush-disk-mode = async
  }

  ## database store  mode=db时，事务日志存储会存储在这个配置的数据库里
  db {
    ## the implement of javax.sql.DataSource, such as DruidDataSource(druid)/BasicDataSource(dbcp) etc.
    datasource = "dbcp"
    ## mysql/oracle/h2/oceanbase etc.
    db-type = "mysql"
    driver-class-name = "com.mysql.jdbc.Driver"
    url = "jdbc:mysql://116.62.62.26/seat-server"  修改这里
    user = "root"  修改这里
    password = "root"  修改这里
    min-conn = 1
    max-conn = 3
    global.table = "global_table"
    branch.table = "branch_table"
    lock-table = "lock_table"
    query-limit = 100
  }
}
```

由于此demo我们使用db模式存储事务日志，所以，我们要**创建三张表：global_table，branch_table，lock_table，建表sql在上面下载的seata-server的/conf/db_store.sql中**；

**由于存储undo_log是在业务库中，所以在每个业务库中，还要创建undo_log表**，建表sql在/conf/db_undo_log.sql中。

由于我自定义了事务组名称，所以这里也做了修改：
```java
service {
  #vgroup->rgroup
  vgroup_mapping.fsp_tx_group = "default"  修改这里，fsp_tx_group这个事务组名称是我自定义的，一定要与client端的这个配置一致！否则会报错！
  #only support single node
  default.grouplist = "127.0.0.1:8091"   此配置作用参考:https://blog.csdn.net/weixin_39800144/article/details/100726116
  #degrade current not support
  enableDegrade = false
  #disable
  disable = false
  #unit ms,s,m,h,d represents milliseconds, seconds, minutes, hours, days, default permanent
  max.commit.retry.timeout = "-1"
  max.rollback.retry.timeout = "-1"
}
```
其他的可以先使用默认值。

##### 2.registry.conf

registry{}中是注册中心相关配置，config{}中是配置中心相关配置。seata中，注册中心和配置中心是分开实现的，是两个东西。

我们这里用eureka作注册中心，所以，只用修改registry{}中的：
```java
registry {
  # 用eureka作注册中心    
  type = "eureka"  

  eureka {
    # eureka作注册中心地址  
    serviceUrl = "http://localhost:8761/eureka"  
    application = "default"  
    weight = "1"
  }
}
```
其他的配置可以暂时使用默认值。

如果是在windows下启动seata-server，现在已经完成配置修改了，等eureka启动后，就可以启动seata-server了：执行/bin/seata-server.bat即可。

### 4.1.seata-server 高可用（要求版本0.9+）

0.9及之前版本，多TC(事务协调者)时，TC会误报异常，此问题0.9之后已经修复，之后的版本应该不会出现此问题。

部署集群，第一台和第二台配置相同，在server端的registry.conf中，注意：

```java
registry {
  # file 、nacos 、eureka、redis、zk、consul、etcd3、sofa
  type = "eureka"
......
  eureka {
    serviceUrl = "http://localhost:8761/eureka"  //两台tc相同,注册中心的地址
    application = "default" //两台tc相同
    weight = "1"  //权重，截至0.9版本，暂时不支持此参数
  }
 ......
```

注意上述配置和client的配置要一致，2台和多台情况相同。

### 5.client端相关配置
#### 1.普通配置
client端的几个服务，都是普通的springboot整合了springCloud组件的正常服务，所以，你需要配置eureka，数据库，mapper扫描等，即使不使用seata，你也需要做，这里不做特殊说明，看代码就好。

#### 2.特殊配置
##### 1.application.yml
以order服务为例，除了常规配置外，这里还要**配置下事务组**信息：
```java
spring:
    application:
        name: order-server
    cloud:
        alibaba:
            seata:		
                # 这个fsp_tx_group自定义命名很重要，server，client都要保持一致
                tx-service-group: fsp_tx_group  
```
##### 2.file.conf
自己新建的项目是没有这个配置文件的，copy过来，修改下面配置：
```java
service {
  #vgroup->rgroup
  # 这个fsp_tx_group自定义命名很重要，server，client都要保持一致    
  vgroup_mapping.fsp_tx_group = "default"   
  #only support single node
  default.grouplist = "127.0.0.1:8091"
  #degrade current not support
  enableDegrade = false
  #disable
  disable = false
  disableGlobalTransaction = false
}
```
##### 3.registry.conf

使用eureka做注册中心，仅需要修改eureka的配置即可：
```java
registry {
  # 使用eureka做注册中心 （还支持file 、nacos 、eureka、redis、zk）
  type = "eureka"

  eureka {
    serviceUrl = "http://localhost:8761/eureka"
    application = "default"
    weight = "1"
  }

}

config {
  # 使用eureka做配置中心 （还支持file、nacos 、apollo、zk）
  type = "file"
  file {
    name = "file.conf"
  }
}

```
其他的使用默认值就好。

#### 3.数据源代理
这个是要特别注意的地方，seata对数据源做了代理和接管，在每个参与分布式事务的服务中，都要做如下配置：

```java
// 在启动类中取消数据源的自动创建
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@MapperScan("io.seata.sample.dao")
@EnableDiscoveryClient
@EnableFeignClients
public class OrderServerApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServerApplication.class, args);
	}

}
```

```java
/**
 * 数据源代理
 * @author wangzhongxiang
 */
@Configuration
public class DataSourceConfiguration {

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSource druidDataSource(){
        DruidDataSource druidDataSource = new DruidDataSource();
        return druidDataSource;
    }

    @Primary
    @Bean("dataSource")
    public DataSourceProxy dataSource(DataSource druidDataSource){
        return new DataSourceProxy(druidDataSource);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSourceProxy dataSourceProxy)throws Exception{
        SqlSessionFactoryBean sqlSessionFactoryBean = new SqlSessionFactoryBean();
        sqlSessionFactoryBean.setDataSource(dataSourceProxy);
        sqlSessionFactoryBean.setMapperLocations(new PathMatchingResourcePatternResolver()
        .getResources("classpath*:/mapper/*.xml"));
        sqlSessionFactoryBean.setTransactionFactory(new SpringManagedTransactionFactory());
        return sqlSessionFactoryBean.getObject();
    }

}
```

### 6.启动测试（注意先后顺序）
- 1.启动eureka;
- 2.启动seata-server;
- 3.启动order,storage,account服务;
- 4.访问：http://localhost:8180/order/create?userId=1&productId=1&count=10&money=100

然后可以模拟正常情况，异常情况，超时情况等，观察数据库即可。

```java
2020-02-04 11:02:13.942  INFO 3560 --- [nio-8761-exec-6] c.n.e.registry.AbstractInstanceRegistry  : Registered instance DEFAULT/192.168.115.1:default:8091 with status UP (replication=false)

2020-02-04 11:03:13.427  INFO 3560 --- [nio-8761-exec-3] c.n.e.registry.AbstractInstanceRegistry  : Registered instance ORDER-SERVER/192.168.233.1:order-server:8180 with status UP (replication=false)

2020-02-04 11:04:08.318  INFO 3560 --- [nio-8761-exec-9] c.n.e.registry.AbstractInstanceRegistry  : Registered instance STORAGE-SERVER/192.168.233.1:storage-server:8182 with status UP (replication=false)


2020-02-04 11:04:48.525  INFO 3560 --- [nio-8761-exec-5] c.n.e.registry.AbstractInstanceRegistry  : Registered instance ACCOUNT-SERVER/192.168.233.1:account-server:8181 with status UP (replication=false)

```

### 7.测试正常情况
**正常情况：**

>  通过我们访问`/order`下单接口，根据响应的内容我们确定商品已经购买成功 
>
>  数据库内的`账户余额`、`商品库存`是否有所扣减 

### 8.测试异常情况
在AccountServiceImpl中模拟异常情况，然后可以查看日志
```java
  /**
 * 账户业务实现类
 */
@Service
public class AccountServiceImpl implements AccountService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AccountServiceImpl.class);
    @Autowired
    private AccountDao accountDao;

    /**
     * 扣减账户余额
     */
    @Override
    public void decrease(Long userId, BigDecimal money) {
        LOGGER.info("------->account-service中扣减账户余额开始");
        //模拟超时异常，全局事务回滚
        try {
            Thread.sleep(30*1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        accountDao.decrease(userId,money);
        LOGGER.info("------->account-service中扣减账户余额结束");
    }
}

```
 此时我们可以发现下单后数据库数据并没有任何改变 

但是，当我们**在seata-order-service中注释掉@GlobalTransactional来看看没有Seata的分布式事务管理会发生什么情况**：

> 由于seata-account-service的超时会导致当库存和账户金额扣减后订单状态并没有设置为已经完成，而且由于远程调用的重试机制，账户余额还会被多次扣减。








