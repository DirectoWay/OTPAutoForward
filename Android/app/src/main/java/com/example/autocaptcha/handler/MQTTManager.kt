package com.example.autocaptcha.handler

import android.content.Context
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MQTTManager(
    private val context: Context,
    private val pairingInfo: PairingInfo
) {
    private val mqttAndroidClient: MqttAndroidClient
    private val options = MqttConnectOptions().apply {
        userName = pairingInfo.username
        password = pairingInfo.password.toCharArray()
        isAutomaticReconnect = true
        isCleanSession = false
    }

    init {
        val serverUri = "tcp://${pairingInfo.broker}:${pairingInfo.port}"
        mqttAndroidClient = MqttAndroidClient(context, serverUri, pairingInfo.clientId)
    }

    fun connect(onConnected: () -> Unit, onFailure: (Throwable?) -> Unit) {
        mqttAndroidClient.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable?) { /* Handle connection lost */ }
            override fun messageArrived(topic: String?, message: MqttMessage?) { /* Handle message */ }
            override fun deliveryComplete(token: IMqttDeliveryToken?) { /* Handle completion */ }
        })

        try {
            mqttAndroidClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = onConnected()
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = onFailure(exception)
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    fun subscribe(topic: String, onSuccess: () -> Unit, onFailure: (Throwable?) -> Unit) {
        try {
            mqttAndroidClient.subscribe(topic, 0, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = onSuccess()
                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) = onFailure(exception)
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }
}