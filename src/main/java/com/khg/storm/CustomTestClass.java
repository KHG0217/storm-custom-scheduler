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
import org.apache.storm.Config;

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

        for (TopologyDetails topology  : cluster.needsSchedulingTopologies()) {
            String topologyName = topology.getName();
            String group = extractGroup(topologyName);

            if (group == null) {
                LOG.info("Group name couldn't be determined for topology: {}, skipping.", topologyName);
                continue;
            }

            // Supervisor 별 실행 중인 그룹 맵핑
            Map<String, Set<String>> supervisorToGroups = getSupervisorToGroups(cluster, topologies);

            // 해당 그룹이 아직 실행되지 않은 Supervisor만 필터

            // 현재 클러스터의 모든 Supervisor 객체들을 가져옴
            // -> Map<String, SueprvisorDetails> 에서 value만 가져옴
            List<SupervisorDetails> candidateSupervisors = cluster.getSupervisors().values().stream()
                    .filter(s -> {
                        Set<String> groups =  supervisorToGroups.get(s.getId()); // 현재 Supervisor가 실행중인 group 목록 가져오기
                        if(groups == null) { // supervisorToGroups에 해당 supervisor가 없다면 아직 아무 group도 실행 안한 supervisor
                            groups = new HashSet<>(); // 빈 Set으로 처리 (null 방지)
                        }
                        return !groups.contains(group); // 현재 스케줄링할 topology에 group이 이미 실행중인지 확인, 없으면 후보군으로 인정
                    })
                    .collect(Collectors.toList()); // 조건을 만족하는 Supervisoir들만 리스트로 수집

            if(candidateSupervisors.isEmpty()) { // 해당 그룸을 이미 모든 Supervisor가 실행 중 -> 실행 안함
                LOG.info("No available supervisor for group: {}, topologt: {}",group,topologyName);
                continue;
            }

            // 가능한 Superviosr 중에서 free slot이 있는 Superviosr만 추출
            List<WorkerSlot> availableSlots = new ArrayList<>();
            for(SupervisorDetails supervisor: candidateSupervisors) {
                Set<Integer> usedPorts = cluster.getUsedPorts(supervisor);
                for(Integer port : supervisor.getAllPorts()) {
                    if(!usedPorts.contains(port)) {
                        availableSlots.add(new WorkerSlot(supervisor.getId(), port));
                    }
                }
            }
            if(availableSlots.isEmpty()) {
                LOG.info("No free worker slots for group: {}, topology: {}", group, topologyName);
                continue;
            }

            // 할당할 executor
            /**
             * cluster.getNeedsSchedulingComponentToExecutors(topology)
             * Cluster 객체에서 topology에 대해 아직 실행되지 않은 componet -> executor 목록을 리턴
             * 반환형 : Map<String, Collection<ExecutorDetails>>
             *     - key: component 이름 (예: spout1, bolt1 등)
             *     - value: 그 component에 연결된 executor들 (하나의 component는 여러 executor을 가질 수 있음)
             *
             * flatMap(Collection::stream)
             * 중첩된 컬렉션을 하나로 풀어주는 역활, List<List<X>> -> List<X>와 같음
             *
             * 예)
             * getNeedsSchedulingComponentToExecutors(topology) 아래의 예시라고 가정
             * {
             *   "spout1": [Executor(1,1), Executor(2,2)],
             *   "bolt1":  [Executor(3,3)]
             * }
             *
             * .values()는
             * [
             *   [Executor(1,1), Executor(2,2)],
             *   [Executor(3,3)]
             * ]
             *
             * -> .flatMap(Collection::stream)으로 펴면
             * [Executor(1,1), Executor(2,2), Executor(3,3)]
             *
             * 즉 해당 로직은 스케줄링이 필요한 모든 executor 목록을 하나의 List로 만드는 로직
             */
            List<ExecutorDetails> executors = cluster.getNeedsSchedulingComponentToExecutors(topology).values().stream()
                    .flatMap(Collection::stream)
                    .collect(Collectors.toList()); // executor들을 List로 수집

            // 사용 가능한 가장 첫 번째 slot에 해당 execytor들을 전부 할당
            try {
                cluster.assign(availableSlots.get(0), topology.getId(), executors);
                LOG.info("Assigned topology: {}, to slot: {}", topologyName, availableSlots.get(0));
            } catch (Exception e) {
                LOG.error("Failed to assign topology: {} to slot: {}", topologyName, availableSlots.get(0), e);
            }
        }
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

    /**
     *
     * @param cluster
     * @param topologies
     * @return
     */
    private Map<String, Set<String>> getSupervisorToGroups (Cluster cluster, Topologies topologies) {
       Map<String, Set<String>> supervisorToGroups = new HashMap<>();

       for (TopologyDetails topologyDetails : topologies.getTopologies()) {
           SchedulerAssignment assignment = cluster.getAssignmentById(topologyDetails.getId());
           if(assignment == null) {
               LOG.info("[SKIP] Topology {} is not yet scheduled, skipping for supervisor-group mapping.", topologyDetails.getName());
               continue;
           }

           String group = extractGroup(topologyDetails.getName());

           for (WorkerSlot slot : assignment.getExecutorToSlot().values()) {
               String supervisorId = slot.getNodeId();
               supervisorToGroups.computeIfAbsent(supervisorId, k -> new HashSet<>()).add(group);
           }
       }
       return supervisorToGroups;
    }

}
