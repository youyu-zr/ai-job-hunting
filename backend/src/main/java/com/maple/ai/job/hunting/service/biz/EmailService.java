package com.maple.ai.job.hunting.service.biz;

import cn.hutool.extra.mail.MailAccount;
import cn.hutool.extra.mail.MailUtil;
import cn.hutool.setting.Setting;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.maple.ai.job.hunting.mapper.UserInfoMapper;
import com.maple.ai.job.hunting.model.bo.UserInfoDO;
import com.maple.ai.job.hunting.model.entity.SendEmailEntity;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

/**
 * 邮件服务
 *
 * @author maple
 * @since 2024/06/26
 */
@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    @Resource
    private TemplateEngine templateEngine;

    @Resource
    private UserInfoMapper userInfoMapper;

    private static final ThreadPoolExecutor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(2, 2,
            0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(6));

    /**
     * Keep the default portable. Deployments with SMTP enabled should provide
     * {@code -Dai.job.mail.setting=...} or {@code AI_JOB_MAIL_SETTING}.
     */
    private static final String DEFAULT_MAIL_SETTING_PATH = "config/mail.setting";


    public void sendEmail(SendEmailEntity sendEmailEntity) {
        // 构建邮件内容
        Context context = new Context(Locale.CHINESE, sendEmailEntity.getParamMap());
        String emailContent = templateEngine.process(sendEmailEntity.getTemplateName(), context);

        // 构建收件人集合
        Set<String> toEmailSet = new HashSet<>();
        if (CollectionUtils.isNotEmpty(sendEmailEntity.getToSendUserSet())) {
            LambdaQueryWrapper<UserInfoDO> condition = new LambdaQueryWrapper<>();
            condition.in(UserInfoDO::getId, sendEmailEntity.getToSendUserSet());
            Set<String> userEmailSets = userInfoMapper.selectList(condition).stream().map(UserInfoDO::getEmail).collect(Collectors.toSet());
            toEmailSet.addAll(userEmailSets);
        }
        if (CollectionUtils.isNotEmpty(sendEmailEntity.getToSendEmailSet())) {
            toEmailSet.addAll(sendEmailEntity.getToSendEmailSet());
        }
        toEmailSet.addAll(loadGlobalNotifyEmails());

        if (CollectionUtils.isEmpty(toEmailSet)) {
            log.warn("邮件未发送：收件人为空 subject:{} template:{}",
                    sendEmailEntity.getSubject(), sendEmailEntity.getTemplateName());
            return;
        }

        // 异步发送邮件；submit 返回的 Future 不读取时会吞掉任务内异常，因此这里显式捕获并记录。
        try {
            THREAD_POOL_EXECUTOR.submit(() -> {
                try {
                    MailAccount account = loadMailAccount();
                    if (account == null) {
                        log.error("邮件发送跳过：运行目录 SMTP 配置不可用 subject:{} template:{}",
                                sendEmailEntity.getSubject(), sendEmailEntity.getTemplateName());
                        return;
                    }
                    MailUtil.send(account, toEmailSet, sendEmailEntity.getSubject(), emailContent, true);
                    log.info("邮件发送成功 subject:{} template:{} toCount:{} queueSize:{}",
                            sendEmailEntity.getSubject(), sendEmailEntity.getTemplateName(), toEmailSet.size(),
                            THREAD_POOL_EXECUTOR.getQueue().size());
                } catch (Exception e) {
                    log.error("邮件发送失败 subject:{} template:{} toCount:{}",
                            sendEmailEntity.getSubject(), sendEmailEntity.getTemplateName(), toEmailSet.size(), e);
                }
            });
        } catch (RuntimeException e) {
            log.error("邮件任务提交失败 subject:{} template:{} toCount:{} queueSize:{}",
                    sendEmailEntity.getSubject(), sendEmailEntity.getTemplateName(), toEmailSet.size(),
                    THREAD_POOL_EXECUTOR.getQueue().size(), e);
        }
    }

    private Set<String> loadGlobalNotifyEmails() {
        String settingPath = Optional.ofNullable(System.getProperty("ai.job.mail.setting"))
                .orElseGet(() -> Optional.ofNullable(System.getenv("AI_JOB_MAIL_SETTING"))
                        .orElse(DEFAULT_MAIL_SETTING_PATH));
        try {
            Setting setting = new Setting(settingPath);
            String notifyTo = setting.getStr("notifyTo");
            if (notifyTo == null || notifyTo.isBlank()) {
                return Collections.emptySet();
            }
            return Arrays.stream(notifyTo.split("[,;\\s]+"))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("读取全局通知邮箱失败 path:{}", settingPath, e);
            return Collections.emptySet();
        }
    }

    private MailAccount loadMailAccount() {
        String settingPath = Optional.ofNullable(System.getProperty("ai.job.mail.setting"))
                .orElseGet(() -> Optional.ofNullable(System.getenv("AI_JOB_MAIL_SETTING"))
                        .orElse(DEFAULT_MAIL_SETTING_PATH));
        try {
            Setting setting = new Setting(settingPath);
            String host = setting.getStr("host");
            String user = setting.getStr("user");
            String pass = setting.getStr("pass");
            if (host == null || host.isBlank() || user == null || user.isBlank() || pass == null || pass.isBlank()) {
                log.error("邮件配置缺少 host/user/pass path:{}", settingPath);
                return null;
            }
            MailAccount account = new MailAccount();
            account.setHost(host);
            account.setPort(setting.getInt("port", 25));
            account.setAuth(setting.getBool("auth", true));
            account.setFrom(setting.getStr("from", user));
            account.setCharset(StandardCharsets.UTF_8);
            account.setUser(user);
            account.setPass(pass);
            account.setSslEnable(setting.getBool("sslEnable", false));
            account.setStarttlsEnable(setting.getBool("starttlsEnable", false));
            account.setTimeout(setting.getLong("timeout", 10000L));
            account.setConnectionTimeout(setting.getLong("connectionTimeout", 10000L));
            account.setWriteTimeout(setting.getLong("writeTimeout", 10000L));
            return account;
        } catch (Exception e) {
            log.error("读取运行目录 SMTP 配置失败 path:{}", settingPath, e);
            return null;
        }
    }
}
