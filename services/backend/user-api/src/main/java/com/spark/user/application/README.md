# 应用层

`application` 保存用例、命令、结果对象和出站端口。

应用层负责组织一次业务用例的执行过程，包括事务边界、幂等、权限、审计和调用领域对象。它可以依赖 `domain` 和应用端口，不能依赖 controller、数据库实现类、消息实现类或第三方 SDK。

后续 LEN-9 可在 `application/loanapplication` 下加入创建申请单、读取草稿等用例。
