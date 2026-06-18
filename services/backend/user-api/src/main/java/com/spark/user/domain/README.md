# 领域层

`domain` 保存领域实体、值对象、领域事件、领域异常和稳定业务不变式。

允许依赖 Java 标准库和团队认可的基础值对象。禁止依赖 Spring、HTTP、ORM、消息、缓存、DI 框架、gRPC 生成物、外部 SDK、`adapter` 或 `infrastructure`。

后续 LEN-9 可在 `domain/loanapplication` 下加入申请单聚合和值对象。
