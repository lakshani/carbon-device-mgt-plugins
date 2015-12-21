/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.transport;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.wso2.carbon.device.mgt.common.DeviceManagementException;
import org.wso2.carbon.device.mgt.iot.config.server.DeviceManagementConfigurationManager;
import org.wso2.carbon.device.mgt.iot.controlqueue.mqtt.MqttConfig;
import org.wso2.carbon.device.mgt.iot.controlqueue.mqtt.MqttSubscriber;
import org.wso2.carbon.device.mgt.iot.sensormgt.SensorDataManager;
import org.wso2.carbon.device.mgt.iot.transport.TransportHandlerException;
import org.wso2.carbon.device.mgt.iot.transport.mqtt.MQTTTransportHandler;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.plugin.constants.VirtualFireAlarmConstants;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.exception.VirtualFireAlarmException;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.util.VerificationManager;
import org.wso2.carbon.device.mgt.iot.virtualfirealarm.service.util.VirtualFireAlarmServiceUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Calendar;
import java.util.UUID;

public class VirtualFireAlarmMQTTSubscriber extends MQTTTransportHandler {
    private static Log log = LogFactory.getLog(VirtualFireAlarmMQTTSubscriber.class);

    private static final String serverName =
            DeviceManagementConfigurationManager.getInstance().getDeviceManagementServerInfo().getName();
    private static final String subscribeTopic =
            serverName + File.separator + "+" + File.separator + VirtualFireAlarmConstants.DEVICE_TYPE +
                    File.separator + "+" + File.separator + "publisher";

    private static final String iotServerSubscriber = UUID.randomUUID().toString().substring(0, 5);
    private String mqttEndpoint;

    private VirtualFireAlarmMQTTSubscriber() {
        super(iotServerSubscriber, VirtualFireAlarmConstants.DEVICE_TYPE,
              MqttConfig.getInstance().getMqttQueueEndpoint(), subscribeTopic);
    }

    public void initConnector() {
        mqttEndpoint = MqttConfig.getInstance().getMqttQueueEndpoint();
    }

    @Override
    public void connect() {
        Runnable connector = new Runnable() {
            public void run() {
                while (!isConnected()) {
                    try {
                        connectToQueue();
                        subscribeToQueue();
                    } catch (TransportHandlerException e) {
                        log.warn("Connection/Subscription to MQTT Broker at: " + mqttBrokerEndPoint + " failed");
                        try {
                            Thread.sleep(timeoutInterval);
                        } catch (InterruptedException ex) {
                            //TODO: Need to print exception
                            log.error("MQTT-Subscriber: Thread Sleep Interrupt Exception");
                        }
                    }
                }
            }
        };

        Thread connectorThread = new Thread(connector);
        connectorThread.setDaemon(true);
        connectorThread.start();
    }

    @Override
    public void processIncomingMessage(MqttMessage mqttMessage, String... messageParams) {
        String topic = messageParams[0];
        String ownerAndId = topic.replace(serverName + File.separator, "");
        ownerAndId = ownerAndId.replace(File.separator + VirtualFireAlarmConstants.DEVICE_TYPE + File.separator, ":");
        ownerAndId = ownerAndId.replace(File.separator + "publisher", "");

        String owner = ownerAndId.split(":")[0];
        String deviceId = ownerAndId.split(":")[1];

        if (log.isDebugEnabled()) {
            log.debug("Received MQTT message for: {OWNER-" + owner + "} & {DEVICE.ID-" + deviceId + "}");
        }

        String actualMessage;

        try {
            PublicKey clientPublicKey = VirtualFireAlarmServiceUtils.getDevicePublicKey(deviceId);
            PrivateKey serverPrivateKey = VerificationManager.getServerPrivateKey();
            actualMessage = VirtualFireAlarmServiceUtils.extractMessageFromPayload(mqttMessage.toString(),
                                                                                   serverPrivateKey, clientPublicKey);
            if (log.isDebugEnabled()) {
                log.debug("MQTT: Received Message [" + actualMessage + "] topic: [" + topic + "]");
            }

            if (actualMessage.contains("PUBLISHER")) {
                float temperature = Float.parseFloat(actualMessage.split(":")[2]);

                if (!VirtualFireAlarmServiceUtils.publishToDAS(owner, deviceId, temperature)) {
                    log.error("MQTT Subscriber: Publishing data to DAS failed.");
                }

                if (log.isDebugEnabled()) {
                    log.debug("MQTT Subscriber: Published data to DAS successfully.");
                }

            } else if (actualMessage.contains("TEMPERATURE")) {
                String temperatureValue = actualMessage.split(":")[1];
                SensorDataManager.getInstance().setSensorRecord(deviceId, VirtualFireAlarmConstants.SENSOR_TEMPERATURE,
                                                                temperatureValue,
                                                                Calendar.getInstance().getTimeInMillis());
            }
        } catch (VirtualFireAlarmException e) {
            String errorMsg =
                    "CertificateManagementService failure oo Signature-Verification/Decryption was unsuccessful.";
            log.error(errorMsg, e);
        }
    }


    @Override
    public void publishDeviceData() {

    }


    @Override
    public void processIncomingMessage() {

    }

    @Override
    public void publishDeviceData(String... publishData) {
        if (publishData.length != 4) {

        }

        String deviceOwner = publishData[0];
        String deviceId = publishData[1];
        String resource = publishData[2];
        String state = publishData[3];

        MqttMessage pushMessage = new MqttMessage();
        String publishTopic =
                serverName + File.separator + deviceOwner + File.separator +
                        VirtualFireAlarmConstants.DEVICE_TYPE + File.separator + deviceId;

        try {
            PublicKey devicePublicKey = VirtualFireAlarmServiceUtils.getDevicePublicKey(deviceId);
            PrivateKey serverPrivateKey = VerificationManager.getServerPrivateKey();

            String actualMessage = resource + ":" + state;
            String encryptedMsg = VirtualFireAlarmServiceUtils.prepareSecurePayLoad(actualMessage,
                                                                                    devicePublicKey,
                                                                                    serverPrivateKey);

            pushMessage.setPayload(encryptedMsg.getBytes(StandardCharsets.UTF_8));
            pushMessage.setQos(DEFAULT_MQTT_QUALITY_OF_SERVICE);
            pushMessage.setRetained(false);

            publishToQueue(publishTopic, pushMessage);

        } catch (VirtualFireAlarmException e) {
            log.error("Preparing Secure payload failed", e);
        } catch (TransportHandlerException e) {
            log.warn("Data Publish attempt to topic - [" + publishTopic + "] failed for payload [" + pushMessage + "]");
        }
    }


    @Override
    public void disconnect() {
        Runnable stopConnection = new Runnable() {
            public void run() {
                while (isConnected()) {
                    try {
                        closeConnection();
                    } catch (MqttException e) {
                        if (log.isDebugEnabled()) {
                            log.warn("Unable to 'STOP' MQTT connection at broker at: " + mqttBrokerEndPoint);
                        }

                        try {
                            Thread.sleep(timeoutInterval);
                        } catch (InterruptedException e1) {
                            log.error("MQTT-Terminator: Thread Sleep Interrupt Exception");
                        }
                    }
                }
            }
        };

        Thread terminatorThread = new Thread(stopConnection);
        terminatorThread.setDaemon(true);
        terminatorThread.start();
    }
}
