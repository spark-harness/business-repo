# Spring Clean Architecture Starter

`spark-spring-clean-architecture-starter` 为 Spring Boot 服务提供干净架构的最小公共支持。

它不是服务脚手架，也不生成 `domain`、`application`、`adapter/inbound` 或 `infrastructure` 目录。目录结构仍由业务服务自己维护。

## Maven 坐标

```xml
<dependency>
  <groupId>com.spark.common</groupId>
  <artifactId>spark-spring-clean-architecture-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## 分层注解

应用层用例使用 `@UseCase`：

```java
@UseCase
public class CreateOrderUseCase {
}
```

入站适配器使用 `@InboundAdapter`：

```java
@InboundAdapter
public class OrderHttpAdapter {
}
```

基础设施实现使用 `@InfrastructureAdapter`：

```java
@InfrastructureAdapter
public class JpaOrderRepository {
}
```

领域层不提供 Spring stereotype。领域对象不应依赖 Spring、HTTP、ORM、消息或缓存框架。

## 事务执行

Starter 在存在 `PlatformTransactionManager` 时自动提供 `TransactionRunner`。

```java
@UseCase
public class CreateOrderUseCase {
    private final TransactionRunner transactionRunner;

    public CreateOrderUseCase(TransactionRunner transactionRunner) {
        this.transactionRunner = transactionRunner;
    }

    public OrderId create(CreateOrderCommand command) {
        return transactionRunner.required(() -> createWithDomain(command));
    }
}
```

只读查询使用 `readOnly`：

```java
public OrderView get(OrderId orderId) {
    return transactionRunner.readOnly(() -> orderReader.get(orderId));
}
```

## gRPC 服务启动

Starter 在服务中存在 `BindableService` bean 时自动启动 gRPC server。

默认配置：

- gRPC 端口：`9090`
- Server Reflection：开启

可通过配置关闭：

```properties
spark.grpc.server.enabled=false
spark.grpc.server.reflection.enabled=false
```

端口可通过配置修改：

```properties
spark.grpc.server.port=9090
```

## 使用边界

- `@UseCase` 只标记应用层用例，不标记 controller、repository 或领域实体。
- `@InboundAdapter` 只标记 HTTP、RPC、消息消费、定时任务等入站入口。
- `@InfrastructureAdapter` 只标记 repository 实现、消息 producer、缓存、第三方 client 等技术实现。
- 出站能力先在 `application` 或 `domain` 定义 port，再由 `infrastructure` 实现。
- 不为领域层引入 Spring 注解。
- gRPC server 生命周期由 starter 托管，业务服务只提供 `BindableService` 入站适配器。
