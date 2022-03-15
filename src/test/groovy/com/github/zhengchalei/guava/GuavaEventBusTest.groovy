package com.github.zhengchalei.guava

import com.google.common.eventbus.EventBus
import com.google.common.eventbus.Subscribe
import spock.lang.Specification

import java.time.LocalDateTime

class Message {
    Message(String message) {
        this.data = message
    }
    def data
}

class Consumer {

}

class GuavaEventBusTest extends Specification {

    @Subscribe
    def handlerMessage(Message message) {
        println("收到消息: $message.data")
    }

    def handlerMessage2(Message message) {
        println("收到消息: $message.data")
    }

    def "event bus"() {
        def eventBus = new EventBus("log_event")
        eventBus.register(this)
        eventBus.post(new Message(LocalDateTime.now().toString()))
        System.in.read()
        expect:
        eventBus != null
    }

}
