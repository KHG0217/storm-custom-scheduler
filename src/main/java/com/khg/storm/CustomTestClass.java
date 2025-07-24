package com.khg.storm;

import org.apache.storm.generated.Assignment;
import org.apache.storm.generated.TopologyStatus;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.scheduler.*;
import org.apache.storm.scheduler.resource.ResourceAwareScheduler;
import org.checkerframework.checker.units.qual.A;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;

public class CustomTestClass implements IScheduler {
    private static final Logger LOG = LoggerFactory.getLogger(CustomTestClass.class);
    private static final Set<String> GROUP_KEYWORDS =
            new HashSet<>(Arrays.asList("youtube","twitter","blog"));
    private IScheduler delegate;

    /**
     * Storm은 토폴로지를 제출할 때 prepare()를 먼저 호출하여 해당 스케줄러를 초기화
     * conf: Storm의 storm.yaml 설정갑이 들어오는 맵 객체
     * 즉, 토폴로지 관련 설정이나 리소스 제한, 사용자 정의 설정들을 conf Map에서 꺼내 쓸 수 있음
     *
     * metricsRegistry: Storm 내부에서 사용하는 메트릭 수집 도구
     * 스케줄러 성능 측정 및 모니터링 지표를 등록
     * null도 허용됨
     *
     * @param conf
     * @param metricsRegistry
     */
    @Override
    public void prepare(Map<String, Object> conf, StormMetricsRegistry metricsRegistry) {
        LOG.info("CustomTestClass, prepare call");
        /**
         * Storm에 기본적으로 제공되는 스케줄러
         * DefaultScheduler: 간단한 라운드로빈 방식
         * ResourceAwareScheduler: CPU, 메모리, 디스크 사용량 등을 고려해서 배치
         * ResourceAwareScheduler를 사용하여 위임(delegate) 용도로 사용
         */
        delegate = new ResourceAwareScheduler();
        delegate.prepare(conf,metricsRegistry);
    }

    /**
     * Storm에서 Topology를 배포할 때 자동으로 호출되는 메서드로,
     * 커스텀 스케줄링 정책을 적용하는 핵심 진입점
     *
     * 본 로직은 다음과 같은 제약 기반 필터 정책을 포함한다:
     *
     * 1. Topology 이름에 포함된 특정 키워드(youtube, twitter, blog 등)를 기반으로 그룹을 분류
     * 2. 현재 실행 중인 Topology들 중, 해당 그룹을 이미 실행 중인 Supervisor 목록을 파악
     * 3. 새로 배치할 Topology가 같은 그룹에 해당하면, 그 그룹을 이미 실행 중인 Supervisor의 사용 가능한 슬롯을 제외시킴
     * 4. 최종적으로 남은 Supervisor들의 슬롯만 가지고 기본 스케줄러(delegate)가 실제 배치를 수행
     *
     * ❗ 즉, 같은 그룹의 Topology는 동시에 같은 Supervisor에서 실행되지 않도록 강제 제한하는 목적의 정책이다.
     *
     * @param topologies Storm에 현재 제출된 모든 Topology의 메타 정보 (실행 중 + 대기 포함)
     * @param cluster Nimbus가 알고 있는 전체 Supervisor 상태, 할당 정보, 실행 슬롯 등의 클러스터 상태
     */
    @Override
    public void schedule(Topologies topologies, Cluster cluster) {
        // 1. Supervisor별로 어떤 그룹을 실행 중인지 기록하는 맵
        Map<String, String> supervisorGroupMap = new HashMap<>();

        // 2. 현재 할당된 모든 토폴로지 조사 → 그룹 키워드가 있는 경우 Supervisor 기록
        // key = Topology ID, value = ScedulerAssignment(어떤 슬롯에서 실행 중인지)
        Map<String, SchedulerAssignment> assignments = cluster.getAssignments();

        for(Map.Entry<String, SchedulerAssignment> entry : assignments.entrySet()) {
            String topologyId = entry.getKey();
            SchedulerAssignment assignment = entry.getValue();

            TopologyDetails td = topologies.getById(topologyId); // TopologyDetails 객체 (이름, 상태 ,자원 등 정보 포함된 객체)
            if (td == null) continue;

            String name = td.getName().toLowerCase();
            String group = extractGroup(name);

            /**
             * 그룹이 있는 경우 (group != null):
             *  이 토폴로지가 할당된 슬롯(slot)의 Supervisor ID(nodeId)를 뽑아 supervisorGroupMap에 기록
             *  즉, 이 Supervisor는 이미 다른 키워드가 포함된 토폴로지ID로 실행중임을 등록
             */
            for(WorkerSlot slot : assignment.getSlots()) {
                if(group != null) {
                    supervisorGroupMap.put(slot.getNodeId(), group);
                }
            }
        }

        // 3. 실행 대기 중인 토폴로지들 중 그룹이 있는 경우, 중복 Supervisor 제한
        for(TopologyDetails td : topologies.getTopologies()) {
            if("ACTIVE".equals(cluster.getStatus(td.getId()))) continue;

            String name = td.getName().toLowerCase();
            String group = extractGroup(name);
            if(group == null) continue; // 그룹에 없는 토폴로지는 제약 없이 실행

            /**
             * 이미 같은 group을 실행하고 있는 Supervisor ID 목록을 모음
             * 이 Supervisor들은 이번에 새로 배치될 토폴로지에서는 배제 대상이 됨
             */
            Set<String> forbiddenSupervisors = supervisorGroupMap.entrySet().stream()
                    .filter(e -> e.getValue().equals(group))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toSet());

            /**
             * [정책 필터 적용]
             * 모든 Supervisor에 대해 반복하며 이미 forbiddenSupervisors 에 포함된 Supervisor라면,
             * 해당 Supervisor에 있는 할당 가능한 슬롯들을 사용하지 못하도록 해제 (freeSlots)
             * 이로 인해 Strom의 기본 스케줄러는 그 Supervisor를 배치 후보에서 제외함
             */
            for(SupervisorDetails supervisor : cluster.getSupervisors().values()) { // 전체 supervisor의 정보 추출
                if(forbiddenSupervisors.contains(supervisor.getId())) { // superviosr의 고유 ID 포함유무, 즉 같은 그룹에 포함된 슈퍼바이저인지 확인
                    LOG.info("제한됨: {} 그룹으로 인해 Supervisor {} 제외됨", group, supervisor.getHost());
                    /**
                     * cluster.freeSlots(...) 해당 슬롯들을 Storm에서 할당하지 않도록 지정
                     * cluster.getAssignableSlots(supervisor) 이 Supervisor에서 사용할 수 있는 슬롯을 반환, 즉 사용하고있지 않는 대기중인 슬롯들
                     */
                    cluster.freeSlots(cluster.getAssignableSlots(supervisor));
                }
            }
        }
        delegate.schedule(topologies, cluster); // 실행: 위임
    }

    @Override
    public Map config() {
        return Collections.emptyMap();
    }

    @Override
    public void cleanup() {
        LOG.info("CustomTestClass, cleanup call");
    }

    private String extractGroup(String name) {
        return GROUP_KEYWORDS.stream()
                .filter(name::contains)
                .findFirst()
                .orElse(null);
    }
}
