package com.antar.cameraorchestrator.config;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.annotation.IntegrationComponentScan;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.config.EnableIntegration;
import org.springframework.integration.core.MessageProducer;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

@Configuration
@EnableIntegration
@IntegrationComponentScan(basePackages = "com.antar.cameraorchestrator.service.adapter.mqtt")
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttConfig {

    private static final Logger log = LoggerFactory.getLogger(MqttConfig.class);

    @Value("${mqtt.broker-url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.client-id:ha_backend_server}")
    private String clientId;

    @Value("${mqtt.username:ha_app}")
    private String username;

    @Value("${mqtt.password:ha_password}")
    private String password;

    @Value("${mqtt.default-qos:1}")
    private int defaultQos;

    @Value("${mqtt.topics.inbound-telemetry:devices/+/telemetry}")
    private String inboundTelemetryTopic;

    @Value("${mqtt.topics.inbound-status:devices/+/status}")
    private String inboundStatusTopic;

    @Value("${zigbee.topics.inbound-pattern:zigbee2mqtt/+}")
    private String zigbeeInboundPattern;

    @Value("${ble.topics.inbound-pattern:ble/+/telemetry}")
    private String bleInboundPattern;

    @Bean
    public MqttPahoClientFactory mqttClientFactory() {
        DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();
        MqttConnectOptions options = new MqttConnectOptions();
        options.setServerURIs(new String[]{brokerUrl});
        if (username != null && !username.isBlank()) {
            options.setUserName(username);
        }
        if (password != null && !password.isBlank()) {
            options.setPassword(password.toCharArray());
        }
        options.setAutomaticReconnect(true);
        options.setCleanSession(true);
        options.setConnectionTimeout(10);
        options.setKeepAliveInterval(60);
        factory.setConnectionOptions(options);
        return factory;
    }

    // Inbound Channel (Telemetry & Status Messages from Devices)
    @Bean
    public MessageChannel mqttInputChannel() {
        return new DirectChannel();
    }

    @Value("${mqtt.auto-startup:true}")
    private boolean autoStartup;

    @Bean
    public MessageProducer inboundMqttAdapter() {
        String adapterClientId = clientId + "_inbound_" + System.currentTimeMillis() % 10000;
        String[] topics = new String[]{
                inboundTelemetryTopic,
                inboundStatusTopic,
                zigbeeInboundPattern,
                "zigbee2mqtt/+/availability",
                "zigbee2mqtt/bridge/+",
                bleInboundPattern,
                "ble/+/status",
                "ble/advertisement",
                "esphome/ble_proxy/+"
        };
        MqttPahoMessageDrivenChannelAdapter adapter =
                new MqttPahoMessageDrivenChannelAdapter(brokerUrl, adapterClientId, mqttClientFactory(), topics);
        adapter.setCompletionTimeout(5000);
        adapter.setConverter(new DefaultPahoMessageConverter());
        adapter.setQos(defaultQos);
        adapter.setAutoStartup(autoStartup);
        adapter.setOutputChannel(mqttInputChannel());
        return adapter;
    }

    // Outbound Channel (Commands to Devices)
    @Bean
    public MessageChannel mqttOutboundChannel() {
        return new DirectChannel();
    }

    @Bean
    @ServiceActivator(inputChannel = "mqttOutboundChannel")
    public MessageHandler mqttOutboundHandler() {
        String adapterClientId = clientId + "_outbound_" + System.currentTimeMillis() % 10000;
        MqttPahoMessageHandler messageHandler = new MqttPahoMessageHandler(adapterClientId, mqttClientFactory());
        messageHandler.setAsync(true);
        messageHandler.setDefaultQos(defaultQos);
        return messageHandler;
    }
}
