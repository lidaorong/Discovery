package com.nepxion.discovery.plugin.configcenter.nacos.adapter;

/**
 * <p>Title: Nepxion Discovery</p>
 * <p>Description: Nepxion Discovery</p>
 * <p>Copyright: Copyright (c) 2017-2050</p>
 * <p>Company: Nepxion</p>
 * @author Haojun Ren
 * @version 1.0
 */

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.nacos.api.config.listener.Listener;
import com.nepxion.discovery.common.constant.DiscoveryConstant;
import com.nepxion.discovery.common.entity.RuleEntity;
import com.nepxion.discovery.common.nacos.constant.NacosConstant;
import com.nepxion.discovery.common.nacos.operation.NacosOperation;
import com.nepxion.discovery.common.nacos.operation.NacosSubscribeCallback;
import com.nepxion.discovery.common.thread.NamedThreadFactory;
import com.nepxion.discovery.plugin.configcenter.adapter.ConfigAdapter;
import com.nepxion.discovery.plugin.framework.adapter.PluginAdapter;
import com.nepxion.discovery.plugin.framework.event.RuleClearedEvent;
import com.nepxion.discovery.plugin.framework.event.RuleUpdatedEvent;

public class NacosConfigAdapter extends ConfigAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(NacosConfigAdapter.class);

    private ExecutorService executorService = new ThreadPoolExecutor(2, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<Runnable>(1), new NamedThreadFactory("nacos-config"), new ThreadPoolExecutor.DiscardOldestPolicy());

    @Autowired
    private PluginAdapter pluginAdapter;

    @Autowired
    private NacosOperation nacosOperation;

    private Listener partialListener;
    private Listener globalListener;

    @Override
    public String getConfig() throws Exception {
        String config = getConfig(false);
        if (StringUtils.isNotEmpty(config)) {
            LOG.info("Found {} config from {} server", getConfigScope(false), getConfigType());

            return config;
        } else {
            LOG.info("No {} config is found from {} server", getConfigScope(false), getConfigType());
        }

        config = getConfig(true);
        if (StringUtils.isNotEmpty(config)) {
            LOG.info("Found {} config from {} server", getConfigScope(true), getConfigType());

            return config;
        } else {
            LOG.info("No {} config is found from {} server", getConfigScope(true), getConfigType());
        }

        return null;
    }

    private String getConfig(boolean globalConfig) throws Exception {
        String group = pluginAdapter.getGroup();
        String serviceId = pluginAdapter.getServiceId();
        String dataId = globalConfig ? group : serviceId;

        return nacosOperation.getConfig(group, dataId);
    }

    @PostConstruct
    public void subscribeConfig() {
        partialListener = subscribeConfig(false);
        globalListener = subscribeConfig(true);
    }

    private Listener subscribeConfig(boolean globalConfig) {
        String group = pluginAdapter.getGroup();
        String serviceId = pluginAdapter.getServiceId();
        String dataId = globalConfig ? group : serviceId;

        LOG.info("Subscribe {} config from {} server, group={}, dataId={}", getConfigScope(globalConfig), getConfigType(), group, dataId);

        try {
            return nacosOperation.subscribeConfig(group, dataId, executorService, new NacosSubscribeCallback() {
                @Override
                public void callback(String config) {
                    if (StringUtils.isNotEmpty(config)) {
                        LOG.info("Get {} config updated event from {} server, group={}, dataId={}", getConfigScope(globalConfig), getConfigType(), group, dataId);

                        RuleEntity ruleEntity = pluginAdapter.getRule();
                        String rule = null;
                        if (ruleEntity != null) {
                            rule = ruleEntity.getContent();
                        }
                        if (!StringUtils.equals(rule, config)) {
                            fireRuleUpdated(new RuleUpdatedEvent(config), true);
                        } else {
                            LOG.info("Updated {} config from {} server is same as current config, ignore to update, group={}, dataId={}", getConfigScope(globalConfig), getConfigType(), group, dataId);
                        }
                    } else {
                        LOG.info("Get {} config cleared event from {} server, group={}, dataId={}", getConfigScope(globalConfig), getConfigType(), group, dataId);

                        fireRuleCleared(new RuleClearedEvent(), true);
                    }
                }
            });
        } catch (Exception e) {
            LOG.error("Subscribe {} config from {} server failed, group={}, dataId={}", getConfigScope(globalConfig), getConfigType(), group, dataId, e);
        }

        return null;
    }

    @Override
    public void close() {
        unsubscribeConfig(partialListener, false);
        unsubscribeConfig(globalListener, true);

        executorService.shutdownNow();
    }

    private void unsubscribeConfig(Listener configListener, boolean globalConfig) {
        if (configListener == null) {
            return;
        }

        String group = pluginAdapter.getGroup();
        String serviceId = pluginAdapter.getServiceId();
        String dataId = globalConfig ? group : serviceId;

        LOG.info("Unsubscribe {} config from {} server, group={}, dataId={}", getConfigScope(globalConfig), getConfigType(), group, dataId);

        nacosOperation.unsubscribeConfig(group, dataId, configListener);
    }

    public String getConfigScope(boolean globalConfig) {
        return globalConfig ? DiscoveryConstant.GLOBAL : DiscoveryConstant.PARTIAL;
    }

    @Override
    public String getConfigType() {
        return NacosConstant.NACOS_TYPE;
    }
}