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
package org.thingsboard.server.queue.discovery;

import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.msg.queue.ServiceKey;
import org.thingsboard.server.common.msg.queue.ServiceType;
import org.thingsboard.server.common.msg.queue.TopicPartitionInfo;
import org.thingsboard.server.gen.transport.TransportProtos;
import org.thingsboard.server.gen.transport.TransportProtos.ServiceInfo;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConsistentHashPartitionService implements PartitionService {

    @Value("${queue.core.topic}")
    private String coreTopic;
    @Value("${queue.core.partitions:100}")
    private Integer corePartitions;
    @Value("${queue.rule_engine.topic}")
    private String ruleEngineTopic;
    @Value("${queue.rule_engine.partitions:100}")
    private Integer ruleEnginePartitions;
    @Value("${queue.partitions.hash_function_name:murmur3_128}")
    private String hashFunctionName;
    @Value("${queue.partitions.virtual_nodes_size:16}")
    private Integer virtualNodesSize;

    private final ApplicationEventPublisher applicationEventPublisher;
    private final TbServiceInfoProvider serviceInfoProvider;
    private final ConcurrentMap<ServiceType, String> partitionTopics = new ConcurrentHashMap<>();
    private final ConcurrentMap<ServiceType, Integer> partitionSizes = new ConcurrentHashMap<>();
    private ConcurrentMap<ServiceKey, List<Integer>> myPartitions = new ConcurrentHashMap<>();
    //TODO: Fetch this from the database, together with size of partitions for each service for each tenant.
    private ConcurrentMap<TenantId, Set<ServiceType>> isolatedTenants = new ConcurrentHashMap<>();
    private ConcurrentMap<TopicPartitionInfoKey, TopicPartitionInfo> tpiCache = new ConcurrentHashMap<>();

    private Map<String, TopicPartitionInfo> tbCoreNotificationTopics = new HashMap<>();
    private Map<String, TopicPartitionInfo> tbRuleEngineNotificationTopics = new HashMap<>();
    private List<ServiceInfo> currentOtherServices;

    private HashFunction hashFunction;

    public ConsistentHashPartitionService(TbServiceInfoProvider serviceInfoProvider, ApplicationEventPublisher applicationEventPublisher) {
        this.serviceInfoProvider = serviceInfoProvider;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @PostConstruct
    public void init() {
        this.hashFunction = forName(hashFunctionName);
        partitionSizes.put(ServiceType.TB_CORE, corePartitions);
        partitionSizes.put(ServiceType.TB_RULE_ENGINE, ruleEnginePartitions);
        partitionTopics.put(ServiceType.TB_CORE, coreTopic);
        partitionTopics.put(ServiceType.TB_RULE_ENGINE, ruleEngineTopic);
    }

    @Override
    public Set<TopicPartitionInfo> getCurrentPartitions(ServiceType serviceType) {
        ServiceInfo currentService = serviceInfoProvider.getServiceInfo();
        TenantId tenantId = getSystemOrIsolatedTenantId(currentService);
        ServiceKey serviceKey = new ServiceKey(serviceType, tenantId);
        List<Integer> partitions = myPartitions.get(serviceKey);
        Set<TopicPartitionInfo> topicPartitions = new LinkedHashSet<>();
        for (Integer partition : partitions) {
            TopicPartitionInfo.TopicPartitionInfoBuilder tpi = TopicPartitionInfo.builder();
            tpi.topic(partitionTopics.get(serviceType));
            tpi.partition(partition);
            if (!tenantId.isNullUid()) {
                tpi.tenantId(tenantId);
            }
            topicPartitions.add(tpi.build());
        }
        return topicPartitions;
    }

    //TODO 2.5 This should return cached TopicPartitionInfo objects instead of creating new one every time.
    @Override
    public TopicPartitionInfo resolve(ServiceType serviceType, TenantId tenantId, EntityId entityId) {
        int hash = hashFunction.newHasher()
                .putLong(entityId.getId().getMostSignificantBits())
                .putLong(entityId.getId().getLeastSignificantBits()).hash().asInt();
        int partition = Math.abs(hash % partitionSizes.get(serviceType));
        boolean isolatedTenant = isIsolated(serviceType, tenantId);
        TopicPartitionInfoKey cacheKey = new TopicPartitionInfoKey(serviceType, isolatedTenant ? tenantId : null, partition);
        return tpiCache.computeIfAbsent(cacheKey, key -> buildTopicPartitionInfo(serviceType, tenantId, partition));
    }

    @Override
    public void recalculatePartitions(ServiceInfo currentService, List<ServiceInfo> otherServices) {
        logServiceInfo(currentService);
        otherServices.forEach(this::logServiceInfo);

        Map<ServiceKey, ConsistentHashCircle<ServiceInfo>> circles = new HashMap<>();
        addNode(circles, currentService);
        for (ServiceInfo other : otherServices) {
            TenantId tenantId = getSystemOrIsolatedTenantId(other);
            addNode(circles, other);
            if (!tenantId.isNullUid()) {
                isolatedTenants.putIfAbsent(tenantId, new HashSet<>());
                for (String serviceType : other.getServiceTypesList()) {
                    isolatedTenants.get(tenantId).add(ServiceType.valueOf(serviceType.toUpperCase()));
                }

            }
        }
        ConcurrentMap<ServiceKey, List<Integer>> oldPartitions = myPartitions;
        TenantId myTenantId = getSystemOrIsolatedTenantId(currentService);
        myPartitions = new ConcurrentHashMap<>();
        partitionSizes.forEach((type, size) -> {
            ServiceKey myServiceKey = new ServiceKey(type, myTenantId);
            for (int i = 0; i < size; i++) {
                ServiceInfo serviceInfo = resolveByPartitionIdx(circles.get(myServiceKey), i);
                if (currentService.equals(serviceInfo)) {
                    ServiceKey serviceKey = new ServiceKey(type, getSystemOrIsolatedTenantId(serviceInfo));
                    myPartitions.computeIfAbsent(serviceKey, key -> new ArrayList<>()).add(i);
                }
            }
        });
        myPartitions.forEach((serviceKey, partitions) -> {
            if (!partitions.equals(oldPartitions.get(serviceKey))) {
                log.info("[{}] NEW PARTITIONS: {}", serviceKey, partitions);
                Set<TopicPartitionInfo> tpiList = partitions.stream()
                        .map(partition -> buildTopicPartitionInfo(serviceKey, partition))
                        .collect(Collectors.toSet());
                applicationEventPublisher.publishEvent(new PartitionChangeEvent(this, serviceKey, tpiList));
            }
        });
        tpiCache.clear();

        if (currentOtherServices == null) {
            currentOtherServices = new ArrayList<>(otherServices);
        } else {
            Set<ServiceKey> changes = new HashSet<>();
            Map<ServiceKey, List<ServiceInfo>> currentMap = getServiceKeyListMap(currentOtherServices);
            Map<ServiceKey, List<ServiceInfo>> newMap = getServiceKeyListMap(otherServices);
            currentOtherServices = otherServices;
            currentMap.forEach((key, list) -> {
                if (!list.equals(newMap.get(key))) {
                    changes.add(key);

                }
            });
            currentMap.keySet().forEach(newMap::remove);
            changes.addAll(newMap.keySet());
            if (!changes.isEmpty()) {
                applicationEventPublisher.publishEvent(new ClusterTopologyChangeEvent(this, changes));
            }
        }
    }

    @Override
    public Set<String> getAllServiceIds(ServiceType serviceType) {
        Set<String> result = new HashSet<>();
        ServiceInfo current = serviceInfoProvider.getServiceInfo();
        if (current.getServiceTypesList().contains(serviceType.name())) {
            result.add(current.getServiceId());
        }
        for (ServiceInfo serviceInfo : currentOtherServices) {
            if (serviceInfo.getServiceTypesList().contains(serviceType.name())) {
                result.add(serviceInfo.getServiceId());
            }
        }
        return result;
    }

    @Override
    public TopicPartitionInfo getNotificationsTopic(ServiceType serviceType, String serviceId) {
        switch (serviceType) {
            case TB_CORE:
                return tbCoreNotificationTopics.computeIfAbsent(serviceId,
                        id -> buildNotificationsTopicPartitionInfo(serviceType, serviceId));
            case TB_RULE_ENGINE:
                return tbRuleEngineNotificationTopics.computeIfAbsent(serviceId,
                        id -> buildNotificationsTopicPartitionInfo(serviceType, serviceId));
            default:
                return buildNotificationsTopicPartitionInfo(serviceType, serviceId);
        }
    }

    private Map<ServiceKey, List<ServiceInfo>> getServiceKeyListMap(List<ServiceInfo> services) {
        final Map<ServiceKey, List<ServiceInfo>> currentMap = new HashMap<>();
        services.forEach(serviceInfo -> {
            for (String serviceTypeStr : serviceInfo.getServiceTypesList()) {
                ServiceType serviceType = ServiceType.valueOf(serviceTypeStr.toUpperCase());
                ServiceKey serviceKey = new ServiceKey(serviceType, getSystemOrIsolatedTenantId(serviceInfo));
                currentMap.computeIfAbsent(serviceKey, key -> new ArrayList<>()).add(serviceInfo);
            }
        });
        return currentMap;
    }

    private TopicPartitionInfo buildNotificationsTopicPartitionInfo(ServiceType serviceType, String serviceId) {
        return new TopicPartitionInfo(serviceType.name().toLowerCase() + ".notifications." + serviceId, null, null, false);
    }

    private TopicPartitionInfo buildTopicPartitionInfo(ServiceKey serviceKey, int partition) {
        return buildTopicPartitionInfo(serviceKey.getServiceType(), serviceKey.getTenantId(), partition);
    }

    private TopicPartitionInfo buildTopicPartitionInfo(ServiceType serviceType, TenantId tenantId, int partition) {
        TopicPartitionInfo.TopicPartitionInfoBuilder tpi = TopicPartitionInfo.builder();
        tpi.topic(partitionTopics.get(serviceType));
        tpi.partition(partition);
        ServiceKey myPartitionsSearchKey;
        if (isIsolated(serviceType, tenantId)) {
            tpi.tenantId(tenantId);
            myPartitionsSearchKey = new ServiceKey(serviceType, tenantId);
        } else {
            myPartitionsSearchKey = new ServiceKey(serviceType, new TenantId(TenantId.NULL_UUID));
        }
        List<Integer> partitions = myPartitions.get(myPartitionsSearchKey);
        if (partitions != null) {
            tpi.myPartition(partitions.contains(partition));
        } else {
            tpi.myPartition(false);
        }
        return tpi.build();
    }

    private boolean isIsolated(ServiceType serviceType, TenantId tenantId) {
        return isolatedTenants.get(tenantId) != null && isolatedTenants.get(tenantId).contains(serviceType);
    }

    private void logServiceInfo(TransportProtos.ServiceInfo server) {
        TenantId tenantId = getSystemOrIsolatedTenantId(server);
        if (tenantId.isNullUid()) {
            log.info("[{}] Found common server: [{}]", server.getServiceId(), server.getServiceTypesList());
        } else {
            log.info("[{}][{}] Found specific server: [{}]", server.getServiceId(), tenantId, server.getServiceTypesList());
        }
    }

    private TenantId getSystemOrIsolatedTenantId(TransportProtos.ServiceInfo serviceInfo) {
        return new TenantId(new UUID(serviceInfo.getTenantIdMSB(), serviceInfo.getTenantIdLSB()));
    }

    private void addNode(Map<ServiceKey, ConsistentHashCircle<ServiceInfo>> circles, ServiceInfo instance) {
        TenantId tenantId = getSystemOrIsolatedTenantId(instance);
        for (String serviceTypeStr : instance.getServiceTypesList()) {
            ServiceType serviceType = ServiceType.valueOf(serviceTypeStr.toUpperCase());
            ServiceKey serviceKey = new ServiceKey(serviceType, tenantId);
            for (int i = 0; i < virtualNodesSize; i++) {
                circles.computeIfAbsent(serviceKey, key -> new ConsistentHashCircle<>()).put(hash(instance, i).asLong(), instance);
            }
        }
    }

    private ServiceInfo resolveByPartitionIdx(ConsistentHashCircle<ServiceInfo> circle, Integer partitionIdx) {
        if (circle == null || circle.isEmpty()) {
            return null;
        }
        Long hash = hashFunction.newHasher().putInt(partitionIdx).hash().asLong();
        if (!circle.containsKey(hash)) {
            ConcurrentNavigableMap<Long, ServiceInfo> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ?
                    circle.firstKey() : tailMap.firstKey();
        }
        return circle.get(hash);
    }

    private HashCode hash(ServiceInfo instance, int i) {
        return hashFunction.newHasher().putString(instance.getServiceId(), StandardCharsets.UTF_8).putInt(i).hash();
    }

    public static HashFunction forName(String name) {
        switch (name) {
            case "murmur3_32":
                return Hashing.murmur3_32();
            case "murmur3_128":
                return Hashing.murmur3_128();
            case "crc32":
                return Hashing.crc32();
            case "md5":
                return Hashing.md5();
            default:
                throw new IllegalArgumentException("Can't find hash function with name " + name);
        }
    }
}