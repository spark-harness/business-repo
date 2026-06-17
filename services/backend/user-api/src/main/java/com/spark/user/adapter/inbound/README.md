# 入站适配层

`adapter/inbound` 保存外部请求进入应用层的入口。

这里负责协议转换、参数校验和错误转换。HTTP controller、gRPC service、消息 consumer 和定时任务 handler 都属于这一层。核心业务规则应在 `domain` 或 `application` 中表达。
