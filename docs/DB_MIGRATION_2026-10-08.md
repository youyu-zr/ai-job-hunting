# 数据库迁移：内测权益幂等

本次自动内测改动依赖 `user_product` 上仅针对 BETA 自动授权行的唯一索引。不能使用对所有产品生效的 `(user_id, product_id, order_id)` 唯一索引，因为邀请兑换会重复使用 `order_id = -1`。

部分历史实例仍有 `uk_beta_user (user_id, product_id)` 唯一索引。当前兼容版可以在不改表的情况下上线：新用户没有 BETA 记录时仍可写入自动授权；已有 BETA 记录时保留原记录，不覆盖历史权益。该索引只有在需要同一用户并存多种 BETA 来源、并且要严格区分历史订单与自动授权时，才需要在确认备份、重复数据和回滚窗口后删除。

```sql
ALTER TABLE ai_job.user_product
    DROP INDEX uk_beta_user,
    ADD COLUMN beta_auto_grant_user_id bigint
        GENERATED ALWAYS AS (
            CASE WHEN product_id = 6 AND order_id = -2 THEN user_id ELSE NULL END
        ) STORED,
    ADD UNIQUE KEY uk_beta_auto_grant_user (beta_auto_grant_user_id);
```

该方案需要支持 STORED generated column 的 MySQL 版本。普通 BETA 自动授权固定使用 `order_id = -2`，唯一索引阻止同一用户重复领取；其他产品及 `order_id = -1` 的邀请兑换不受影响。

## 迁移前检查

先确认数据库版本以及表结构，不要假设每个 MySQL 兼容服务都支持相同的 STORED generated column 行为：

```sql
SELECT VERSION();
SHOW CREATE TABLE ai_job.user_product;
```

如果 `beta_auto_grant_user_id` 或 `uk_beta_auto_grant_user` 已存在，先对照实际定义再决定后续操作，不要重复执行 `ADD COLUMN`。

先检查会阻止索引建立的重复数据：

```sql
SELECT user_id, product_id, order_id, COUNT(*) AS row_count
FROM ai_job.user_product
WHERE product_id = 6 AND order_id = -2
GROUP BY user_id, product_id, order_id
HAVING COUNT(*) > 1;
```

不要直接删除重复行。先备份 `user_product`，核对有效期和创建时间，再由实例维护者保留有效期最晚且来源可解释的一条。无法判断时应停在这里，不要为了让迁移通过而自动清理。

如果表上已经存在此前使用过的 `uk_user_product_order`，先检查索引：

```sql
SHOW INDEX FROM ai_job.user_product;
```

如果存在旧的 `uk_beta_user`，兼容版不要求立即删除。只有在准备让同一用户同时保留历史 BETA、邀请 BETA 和自动 BETA 时，才应先备份 `user_product`，确认没有需要保留的重复行，再在同一条 ALTER 中删除它并添加上面的 BETA 专用索引；不要把 `DROP INDEX` 和加新索引直接套用到未检查的生产库。

如果存在其他唯一索引，例如 `uk_user_product_order`，先单独评估它是否会破坏邀请兑换或历史订单语义。不要为了让迁移通过而自动删除未知索引。

## 迁移后验证

```sql
SHOW INDEX FROM ai_job.user_product
WHERE Key_name = 'uk_beta_auto_grant_user';
```

之后用同一账号重复刷新权益查询，确认不会新增 BETA 行；再用两个并发请求做受控测试，确认最终只保留一条固定 `order_id = -2` 的记录，并确认同一用户仍可重复获得不同批次的邀请权益。不要在真实批量投递过程中做这项迁移。

回滚前应先停止新版本或切回旧版本，再根据迁移前的 `SHOW CREATE TABLE` 和表备份恢复索引定义。不要在未确认旧版本是否兼容多条 BETA 记录前直接恢复 `uk_beta_user`。

## BOSS 用户标识唯一性

简历导入按 `user_info.unique_id` 查找用户。并发首次导入时，如果数据库允许同一 BOSS UID 出现多行，后续 `selectOne` 会失败，因此新建数据库结构增加了 `uk_user_info_unique_id`。已有数据库需要先确认没有重复 UID，再单独迁移；不要直接在生产库执行未经检查的 ALTER。

```sql
SELECT unique_id, COUNT(*) AS row_count, GROUP_CONCAT(id ORDER BY id) AS user_ids
FROM ai_job.user_info
WHERE unique_id IS NOT NULL
GROUP BY unique_id
HAVING COUNT(*) > 1;
```

若查询返回记录，先备份并人工核对关联的简历、产品权益、邀请和会话数据，确认应合并的用户关系后再处理；无法确认归属时停止迁移。无重复且已确认备份和回滚窗口后，才添加索引：

```sql
ALTER TABLE ai_job.user_info
    ADD UNIQUE KEY uk_user_info_unique_id (unique_id);
```

迁移后用 `SHOW INDEX FROM ai_job.user_info WHERE Key_name = 'uk_user_info_unique_id';` 验证。新版本把用户、简历和自动内测权益写入放在同一个数据库事务中，避免简历更新失败时删除旧简历或留下不完整用户记录。

## 每用户单份简历约束

简历导入和读取均按 `user_id` 视为单份简历。新建 schema 为 `user_resume.user_id` 增加唯一索引。已有实例先检查重复关系：

```sql
SELECT user_id, COUNT(*) AS row_count, GROUP_CONCAT(id ORDER BY id) AS resume_ids
FROM ai_job.user_resume
WHERE user_id IS NOT NULL
GROUP BY user_id
HAVING COUNT(*) > 1;
```

如有重复，先备份并核对简历版本、账号关系及对应用户，不要自动选一份删除。清理并确认无重复后，可在维护窗口单独添加：

```sql
ALTER TABLE ai_job.user_resume
    ADD UNIQUE KEY uk_user_resume_user_id (user_id);
```

事务只能保证单次导入内用户、简历和权益的数据库变更一起提交或回滚；跨进程并发仍需以上数据库唯一约束兜底。
