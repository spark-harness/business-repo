# 基础设施层

`infrastructure` 保存数据库、消息、缓存、第三方 client 和其他技术实现。

基础设施层实现 `application` 或 `domain` 定义的端口。业务层不直接 new 或 import 基础设施实现类。
