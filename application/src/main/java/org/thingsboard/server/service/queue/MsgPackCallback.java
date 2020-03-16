/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.queue;

import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.TbProtoQueueMsg;

import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

@Slf4j
public class MsgPackCallback<T extends com.google.protobuf.GeneratedMessageV3> implements TbMsgCallback {
    private final CountDownLatch processingTimeoutLatch;
    private final ConcurrentMap<UUID, TbProtoQueueMsg<T>> ackMap;
    private final UUID id;

    public MsgPackCallback(UUID id, CountDownLatch processingTimeoutLatch, ConcurrentMap<UUID, TbProtoQueueMsg<T>> ackMap) {
        this.id = id;
        this.processingTimeoutLatch = processingTimeoutLatch;
        this.ackMap = ackMap;
    }

    @Override
    public void onSuccess() {
        if (ackMap.remove(id) != null && ackMap.isEmpty()) {
            processingTimeoutLatch.countDown();
        }
    }

    @Override
    public void onFailure(Throwable t) {
        TbProtoQueueMsg<T> message = ackMap.remove(id);
        log.warn("Failed to process message: {}", message.getValue(), t);
        if (ackMap.isEmpty()) {
            processingTimeoutLatch.countDown();
        }
    }
}