# MEMORY · project
# 一条一个事实。正文超过 400 字符会被拒；同名 key 覆盖旧值；超过 30 条淘汰最久没读的。

## api-error-shape
updated: 2026-10-10T09:29:00
last-read: 2026-10-10T10:29:00
reads: 1
这个项目的接口错误返回固定是 {"error": {"code": "ORDER_NOT_FOUND", "message": "..."}}，没有 type 字段，也没有 OpenAI 那层 error.type。所有解析都先看 error.code，别照 SDK 示例写 error.type。HTTP 状态码不一定是 200，成功与否先判有没有 error 节点。S09_SEED_TAIL

## build-command
updated: 2026-10-10T09:28:00
last-read: 2026-10-10T10:28:00
reads: 2
构建只用 mvn -q clean compile，不要用 mvn install。install 会把这个快照覆盖进本地仓库，别的模块拉到的就是未提交的代码，排查起来极难。要装到本地就用 -DskipTests 并且改版本号后缀 -SNAPSHOT。

## log-timezone
updated: 2026-10-10T09:27:00
last-read: 2026-10-10T10:27:00
reads: 3
data 下的慢查询日志时间戳都是 UTC，行尾带 Z，例：2026-11-21T10:02:01Z。报给人看之前先加 8 小时，并且说明是北京时间。写进 notes 的文件里两种时间不要混，统一用 UTC 加一行注释说明。

## slow-threshold
updated: 2026-10-09T09:26:00
last-read: 2026-10-09T10:26:00
reads: 4
慢查询阈值订的是 1000ms，低于这个数的不进报告。900ms 的也别列，不是它不慢，是报告只有三个位置，把 900ms 放进去会挤掉真正要改的那条。阈值在 config/slow.yml 里，不要硬编码在脚本里。

## index-naming
updated: 2026-10-09T09:25:00
last-read: 2026-10-09T10:25:00
reads: 1
索引命名统一 idx_<表名>_<字段1>_<字段2>，例：idx_orders_user_id_created_at。字段顺序按区分度从高到低，不要按 SQL 里出现的顺序。联合索引加完要顺手删掉被它覆盖的单列索引，否则写入会多付一份维护成本。

## api-orders
updated: 2026-10-09T09:24:00
last-read: 2026-10-09T10:24:00
reads: 2
/api/orders 慢是缺 (user_id, created_at) 联合索引，只加 user_id 的单列索引没用：查询里 created_at 有范围条件，单列索引扫完还要回表排序。加完预计从 4200ms 降到 200ms 以内，影响行数约 3 万，回滚就是 DROP INDEX。

## api-feed
updated: 2026-10-08T09:23:00
last-read: 2026-10-08T10:23:00
reads: 3
/api/feed 的 LIKE '%kw%' 前缀通配用不上任何 B+ 树索引，加索引是白费。要么改全文检索（ngram 分词），要么把搜索拆到单独的搜索服务。这条别写进索引建议里，写了会被 DBA 打回来。

## api-report
updated: 2026-10-08T09:22:00
last-read: 2026-10-08T10:22:00
reads: 4
/api/report 的 count(*) 在大表上扫全表，上预聚合表 report_daily 按天累加，查询时只读几十行。在这条 SQL 上调索引没用，因为它要的就是全表计数。预聚合表要跟订单状态机对齐，退款要回减。

## db-pool
updated: 2026-10-08T09:21:00
last-read: 2026-10-08T10:21:00
reads: 1
连接池上限 20，HikariCP 的 maximumPoolSize 在 application.yml 里。加完索引要顺带看一眼 p99 是不是变成了等连接：SQL 快了但连接不够，表现是 p99 不降反升，这种情况加连接数而不是继续调 SQL。

## test-db
updated: 2026-10-07T09:20:00
last-read: 2026-10-07T10:20:00
reads: 2
本地测试库在 docker compose 的 mysql-test 服务里，端口 3307，不是 3306——3306 是本机装的那个。连错了会发现表是空的，别以为是数据没导进去。compose 文件在 deploy/docker-compose.test.yml。

## java-version
updated: 2026-10-07T09:19:00
last-read: 2026-10-07T10:19:00
reads: 3
本机 JDK 21，record、switch 模式匹配、虚拟线程都可以直接用，不要为了兼容写 17 的降级写法。但 pom 里 compiler.release 统一写 21，别有的模块写 17——混着写会在 CI 上炸，本地却看不出来。

## jackson-shape
updated: 2026-10-07T09:18:00
last-read: 2026-10-07T10:18:00
reads: 4
Jackson 里取缺失字段用 path()，get() 返回 null 会直接 NPE。isMissingNode() 判存在，asText("") 给默认值。这两个混用是这个仓库最常见的空指针来源，尤其是解析工具返回值的时候。

## http-client
updated: 2026-10-06T09:17:00
last-read: 2026-10-06T10:17:00
reads: 1
调外部接口统一用 java.net.http.HttpClient，不引 OkHttp。一个 HttpClient 实例复用，别每次 new，连接池在里面。超时两个都要设：connectTimeout(3s) 和请求级 timeout(10s)，只设一个等于没设。

## retry-policy
updated: 2026-10-06T09:16:00
last-read: 2026-10-06T10:16:00
reads: 2
外部调用失败重试 3 次，间隔 200/600/1800ms，指数退避。不要写死 sleep(1)，也不要重试 4xx：参数错了重试一百次还是错，只会把日志刷满。重试要打在幂等接口上，非幂等的先加幂等键。

## verdict-file
updated: 2026-10-06T09:15:00
last-read: 2026-10-06T10:15:00
reads: 3
结论写到 notes/verdict.txt，不要写 README。README 是给人看的项目说明，报告是一次性的，而且每次任务都会覆盖。notes/ 已在 .gitignore 里，别指望它进仓库。

## sql-review
updated: 2026-10-05T09:14:00
last-read: 2026-10-05T10:14:00
reads: 4
索引建议必须带三样：预计影响行数、预计耗时变化、回滚语句。只写 CREATE INDEX 不算建议，DBA 会打回来重写。格式参考 notes/verdict.txt 里上一次的写法，一条一行，别写成一整段。

## commit-prefix
updated: 2026-10-05T09:13:00
last-read: 2026-10-05T10:13:00
reads: 1
提交信息用 feat/fix/chore/docs 前缀，冒号后中文，例：fix: 修正慢查询日志的时区换算。不要写 update、修改一下这种。正文超过一行就换行写原因，不要堆在标题里。

## branch-name
updated: 2026-10-05T09:12:00
last-read: 2026-10-05T10:12:00
reads: 2
分支名 feature/<短横线主题>，例：feature/context-compact。修 bug 用 fix/<主题>。不要用日期当分支名，也不要直接在 main 上改——CI 只在 PR 上跑，直接推 main 等于跳过检查。

## review-owner
updated: 2026-10-04T09:11:00
last-read: 2026-10-04T10:11:00
reads: 3
SQL 相关的改动 review 找 DBA 组的老王，其他人只能给意见不能批。涉及金额、库存的改动再加一个业务方，两个人都点了才能合。找不到人就在群里 @，别自己给自己 approve。

## prod-window
updated: 2026-10-04T09:10:00
last-read: 2026-10-04T10:10:00
reads: 4
生产变更窗口是周二、周四 20:00-22:00，其他时间只允许回滚。窗口外要发版得走紧急流程，要值班经理在群里确认。加索引这种 DDL 也要走窗口，大表加索引会锁表。

## metrics
updated: 2026-10-04T09:09:00
last-read: 2026-10-04T10:09:00
reads: 1
压测看 p99 不看平均值：平均值会把长尾吃掉，1% 的用户等 5 秒，平均下来只多 50ms。看板在 Grafana 的 svc-api 面板，p99 那条线是红色的。报数据的时候带上采样时间窗。

## cache-ttl
updated: 2026-10-03T09:08:00
last-read: 2026-10-03T10:08:00
reads: 2
商品详情缓存 TTL 60 秒，不要调大。库存变了页面会挂旧价格，客诉比缓存穿透更难处理。缓存 key 是 item:detail:<id>，改结构要一起改预热脚本，否则上线后第一批请求全穿透。

## feature-flag
updated: 2026-10-03T09:07:00
last-read: 2026-10-03T10:07:00
reads: 3
新逻辑一律挂 feature flag，名字 f_<模块>_<动作>，默认关。开关在配置中心，改完 30 秒生效，不用发版。上线两周确认没问题再删 flag 和旧分支，flag 留着不删，半年后没人敢动那段代码。

## error-log
updated: 2026-10-03T09:06:00
last-read: 2026-10-03T10:06:00
reads: 4
错误日志用 log.error(msg, e) 把异常对象传进去，不要 log.error(e.getMessage())——后者丢掉堆栈，线上只能看到一句 null，查不出在哪炸的。日志里带上 trace id 和关键参数，不要打整个请求体。

## trace-id
updated: 2026-10-02T09:05:00
last-read: 2026-10-02T10:05:00
reads: 1
trace id 从网关透传，服务内部不要自己生成新的。透传头是 X-Trace-Id，MDC 里放 traceId，日志格式里已经带了。自己生成会让链路断在中间，排查时只能一段一段对时间戳。

## json-null
updated: 2026-10-02T09:04:00
last-read: 2026-10-02T10:04:00
reads: 2
返回体里的 null 字段用 @JsonInclude(NON_NULL) 去掉，前端按字段缺失处理，不要返回 null 又让前端判空。但数组要返回 []，不要返回 null——前端 map 一个 null 直接白屏。

## pagination
updated: 2026-10-02T09:03:00
last-read: 2026-10-02T10:03:00
reads: 3
分页默认 20 条，上限 100，超过上限截断而不是报错。参数名 page/size，从 1 开始，不是 0。深分页超过 1000 页要走游标，limit offset 到这个量级会扫全表，慢查询里一半是这么来的。

## auth-header
updated: 2026-10-01T09:02:00
last-read: 2026-10-01T10:02:00
reads: 4
鉴权头是 Authorization: Bearer <token>，网关校验，服务里只读不写。不要在业务代码里改这个头，也不要把它打进日志——日志平台是全组可见的，token 泄露比 bug 严重。

## deploy
updated: 2026-10-01T09:01:00
last-read: 2026-10-01T10:01:00
reads: 1
发布用 scripts/deploy.sh，不要手敲 kubectl。脚本里有灰度暂停：先 10% 流量观察 5 分钟，看错误率和 p99 正常才继续。跳过脚本直接 apply，出了问题回滚要手工对版本，很麻烦。

## rollback
updated: 2026-10-01T09:00:00
last-read: 2026-10-01T10:00:00
reads: 2
回滚就是把镜像 tag 换回上一个 git sha，不是 latest。scripts/rollback.sh 接受一个 sha 参数，默认取上一次部署记录。回滚不做数据库迁移回退，DDL 要提前设计成向后兼容。
