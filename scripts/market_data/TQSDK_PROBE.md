# TqSdk 本地只读行情探针

此工具只为一个**明确到期月**的 DCE/CZCE 合约验证本机接口形状与源时间，不发布价格、不写数据库、不连接商情看板、不下单。官方文档说明免费版提供国内期货实时行情，快期账户用于 `TqAuth` 登录；这并不证明个人账号有权向企业员工展示、缓存或再分发行情。

## 使用边界

1. 在隔离的 Python 3.11 环境安装官方 `tqsdk==3.10.2`，不要安装到正式业务服务的 Python 环境。
2. 用供应商界面核对仍在交易的实际到期月合约。先执行无网络的检查模式：`python scripts/market_data/tqsdk_probe.py --symbol DCE.m2701`。这里的代码只是**格式示例**，不是已核验或推荐交易合约。
3. 在个人本机交互终端执行 `python scripts/market_data/tqsdk_probe.py --symbol <已核验合约> --run`。工具在终端分别请求账号与不回显的密码；不要把凭据写在命令行、聊天、仓库或报告中。
4. 结果只输出合约身份、是否有有效价格字段、供应商行情时间及相对时间。`SOURCE_RECENT` 仅表示本次个人会话收到相近时间的报价；`dashboardReady` 始终为 `false`。非交易时段可能返回 `SOURCE_STALE`，应保留真实源时间。

工具不运行订阅守护进程，也没有公开 HTTP 端口。取得企业展示与缓存许可、确认品种和价格单位、实际字段与失权行为、并验证持续更新后，才可单独设计受控网关接入。公网正式切换仍需独立满足写入隔离、同窗口备份恢复及运行时门禁。

参考：[TqSdk 行情与合约文档](https://doc.shinnytech.com/tqsdk/latest/usage/mddatas.html)、[Quote 字段说明](https://doc.shinnytech.com/tqsdk/latest/reference/tqsdk.objs.html)、[更新循环](https://doc.shinnytech.com/tqsdk/latest/usage/framework.html)、[快期账户](https://doc.shinnytech.com/tqsdk/latest/usage/shinny_account.html)。
