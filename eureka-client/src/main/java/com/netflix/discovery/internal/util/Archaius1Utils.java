package com.netflix.discovery.internal.util;

import com.netflix.config.ConfigurationManager;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * This is an INTERNAL class not for public use.
 *
 * @author David Liu
 */
public final class Archaius1Utils {

    private static final Logger logger = LoggerFactory.getLogger(Archaius1Utils.class);

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";
    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    /**
     * 初始化配置文件
     *
     * @param configName 配置文件名称
     * @return 加载后的配置属性
     */
    public static DynamicPropertyFactory initConfig(String configName) {
        DynamicPropertyFactory configInstance = DynamicPropertyFactory.getInstance();
        // 配置文件名
        DynamicStringProperty EUREKA_PROPS_FILE = configInstance.getStringProperty("eureka.client.props", configName);

        String env = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT, "test");
        ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, env);

        // 将配置文件加载到环境变量
        String eurekaPropsFile = EUREKA_PROPS_FILE.get();
        try {
            // 读取配置文件到环境变量，首先读取 ${eureka.client.props} 对应的配置文件
            // 然后读取 ${eureka.client.props}-${eureka.environment} 对应的配置文件。若有相同属性，进行覆盖
            ConfigurationManager.loadCascadedPropertiesFromResources(eurekaPropsFile);
        } catch (IOException e) {
            logger.warn(
                    "Cannot find the properties specified : {}. This may be okay if there are other environment "
                            + "specific properties or the configuration is installed with a different mechanism.",
                    eurekaPropsFile);

        }

        return configInstance;
    }
}
