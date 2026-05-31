# user-api

`user-api` 是 Spring Boot 后端服务，使用团队后端干净架构目录。

## 目录

```text
src/main/java/com/spark/user/
├── bootstrap/
├── domain/
├── application/
├── adapter/inbound/
└── infrastructure/
```

## 分层边界

- `domain`：领域模型、值对象、领域事件和领域异常。
- `application`：用例、命令、结果对象和出站 port。
- `adapter/inbound`：HTTP、RPC、消息消费和定时任务入口。
- `infrastructure`：数据库、消息、缓存、第三方 client 等出站实现。
- `bootstrap`：Spring Boot 启动和装配入口。

## 本地运行

```bash
mvn spring-boot:run
```

服务依赖 `spark-spring-clean-architecture-starter` 托管 gRPC server。默认同时启动：

- HTTP：`8080`
- gRPC：`9090`
- gRPC Server Reflection：默认开启

gRPC ping 契约：

- service：`vesta.spark.user.v1.PingService`
- method：`Ping`
- request：`name`
- response：`message = "pong, {name}"`

当 `name` 缺失或为空白时，服务返回 gRPC `INVALID_ARGUMENT`。

## 契约依赖

服务通过 GitHub Packages 消费 Java IDL 生成物：

```xml
<dependency>
  <groupId>com.spark.contract</groupId>
  <artifactId>spark-idl-java</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

本地 Maven 需要在 `~/.m2/settings.xml` 中配置与 `pom.xml` repository id 匹配的 `github` 凭据：

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>${env.GITHUB_ACTOR}</username>
      <password>${env.GITHUB_TOKEN}</password>
    </server>
  </servers>
</settings>
```

## 本地测试

```bash
mvn test
```
