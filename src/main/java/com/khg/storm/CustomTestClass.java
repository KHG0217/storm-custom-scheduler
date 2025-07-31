package com.khg.storm;
import org.apache.storm.metric.StormMetricsRegistry;
import org.apache.storm.scheduler.*;
import org.apache.storm.scheduler.resource.ResourceAwareScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            List<SupervisorDetails> candidateSupervisors = cluster.getSupervisors().values().stream()    // 현재 클러스터의 모든 Supervisor 객체들을 가져옴
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
            LOG.info("candidateSupervisors: ");
            candidateSupervisors.forEach(k -> LOG.info("Supervisor: {}",k));

            // 중복 방지용 Set 추가
            Set<String> assignedSupervisors = new HashSet<>();

            // 가능한 Superviosr 중에서 free slot이 있는 Superviosr만 추출
            List<WorkerSlot> availableSlots = new ArrayList<>();
            for(SupervisorDetails supervisor: candidateSupervisors) {
                if (assignedSupervisors.contains(supervisor.getId())) {
                    LOG.warn("Supervisor {} already assigned in this scheduling cycle. Skipping...", supervisor.getId());
                    continue;
                }

                Set<Integer> usedPorts = cluster.getUsedPorts(supervisor);
                LOG.info("usePorts: " + usedPorts);
                for(Integer port : supervisor.getAllPorts()) {
                    if(!usedPorts.contains(port)) {
                        availableSlots.add(new WorkerSlot(supervisor.getId(), port));
                        LOG.info("사용가능: {},{}",supervisor.getHost(),port );
                    }
                }
                // 한 번이라도 슬롯이 추가되면 해당 Supervisor는 이번 루프에서 사용했다고 기록
                if (!availableSlots.isEmpty()) {
                    assignedSupervisors.add(supervisor.getId());
                    LOG.info("assignedSupervisors add: " + supervisor.getId());
                }
            }

            if(availableSlots.isEmpty()) {
                LOG.info("No free worker slots for group: {}, topology: {}", group, topologyName);
                continue;
            }
            try {
                /**
                 * cluster.getNeedsSchedulingComponentToExecutors(topology)
                 * Cluster 객체에서 topology에 대해 아직 실행되지 않은 componet -> executor 목록을 리턴
                 * 반환형 : Map<String, Collection<ExecutorDetails>>
                 *     - key: component 이름 (예: spout1, bolt1 등)
                 *     - value: 그 component에 연결된 executor들 (하나의 component는 여러 executor을 가질 수 있음)
                 */

                Map<String, ? extends Collection<ExecutorDetails>> executors =
                        cluster.getNeedsSchedulingComponentToExecutors(topology);
                LOG.info("executors : " + executors.entrySet());

                Map<String, Queue<ExecutorDetails>> componentQueues = new HashMap<>();
                // component → queue 형태로 전환
                for (Map.Entry<String, ? extends Collection<ExecutorDetails>> entry : executors.entrySet()) {
                    componentQueues.put(entry.getKey(), new LinkedList<>(entry.getValue()));
                }
                int targetWorkerCount = topology.getNumWorkers(); // topology에 단긴 worker수
                LOG.info("targetWorkerCount: " + targetWorkerCount);

                /**
                 * componet 필드별로 perworker할당
                 */
                Map<String ,Integer> perWorkerCount = new HashMap<>();
                for(Map.Entry<String, Queue<ExecutorDetails>> entry : componentQueues.entrySet()) {
                    String component = entry.getKey();
                    int totalExecutors = entry.getValue().size();
                    int perWorker = (int) Math.ceil((double) totalExecutors / targetWorkerCount);
                    perWorkerCount.put(component, perWorker);
                    LOG.info("perWorkerCount: {}", perWorkerCount.entrySet());
                }

                // Supervisor 기준으로 묶기
                Map<String, List<WorkerSlot>> supervisorToSlots = new HashMap<>();
                for (WorkerSlot slot : availableSlots) {
                    supervisorToSlots.computeIfAbsent(slot.getNodeId(), k -> new ArrayList<>()).add(slot);
                    LOG.info("supervisorToSlots: " + supervisorToSlots.entrySet());
                }

                /**
                 * supervisorToSlots 예시
                 * supervisorToSlots: [
                 *   55200935-3a72-47cc-9b1c-3b7a5e39d8aa-10.34.32.189 = [ //supervisor
                 *       55200935-3a72-47cc-9b1c-3b7a5e39d8aa-10.34.32.189:6700, supervisor:port1
                 *       55200935-3a72-47cc-9b1c-3b7a5e39d8aa-10.34.32.189:6701, supervisor:port2
                 *       55200935-3a72-47cc-9b1c-3b7a5e39d8aa-10.34.32.189:6702 supervisor:port3
                 *   ],
                 *   905b74d6-702d-435d-8c5c-840c65945541-10.34.31.176 = [
                 *       905b74d6-702d-435d-8c5c-840c65945541-10.34.31.176:6700,
                 *       905b74d6-702d-435d-8c5c-840c65945541-10.34.31.176:6701,
                 *       905b74d6-702d-435d-8c5c-840c65945541-10.34.31.176:6702
                 *   ],
                 *   0093d165-df4d-4684-abd7-89bb16625e61-10.33.30.89 = [
                 *       0093d165-df4d-4684-abd7-89bb16625e61-10.33.30.89:6700,
                 *       0093d165-df4d-4684-abd7-89bb16625e61-10.33.30.89:6701,
                 *       0093d165-df4d-4684-abd7-89bb16625e61-10.33.30.89:6702
                 *   ]
                 * ]
                 */
                List<WorkerSlot> selectedSlots = supervisorToSlots.values().stream()
                        .filter(slots -> !slots.isEmpty())
                        .limit(targetWorkerCount)  // 워커 수만큼 제한
                        /**
                         * targetWorkerCount > 사용가능 Superviosr수 일 경우
                         * 예)
                         * targetWorkerCount: 4개
                         * Superviosr수: 3대
                         * 실제 할당되는 워커 슬롯은 3개
                         * 이때 Storm은 모든 executor가 worker에 배치되지 않으면 "needsScheduling" 상태로 토폴로지를 유지하게 됨
                         * 스케줄러가 실시간으로 감지하여 할당할 수 있는 워커 수가 되어 할당이 완료될때 까지 재시도
                         *
                         * 관련 스톰 설정
                         * nimbus.monitor.freq.secs	N :
                         * Nimbus가 클러스터 상태(할당되지 않은 Executor 포함)를 확인하고
                         * 재스케줄링을 시도하는 주기(초 단위). 워커가 부족하면 N초마다 반복 시도
                         *
                         */
                        .map(slots -> slots.get(0)) // 각 supervisor에서 첫 번째 슬롯만
                        .collect(Collectors.toList());
                LOG.info("selectedSlots: " + selectedSlots);

                for (int i = 0; i < selectedSlots.size(); i++) {
                    List<ExecutorDetails> executorList = new ArrayList<>();

                    for(Map.Entry<String, Queue<ExecutorDetails>> entry : componentQueues.entrySet()) {
                        String component = entry.getKey();
                        LOG.info("component: " + component);
                        Queue<ExecutorDetails> queue = entry.getValue();
                        LOG.info("queue: " + queue);
                        int perWorker = perWorkerCount.getOrDefault(component, 1);

                        for(int j = 0; j < perWorker; j++) {
                            ExecutorDetails exec = queue.poll();
                            if(exec != null) {
                                executorList.add(exec);
                            } else {
                                break;
                            }
                        }
                    }
                    if(!executorList.isEmpty()) {
                        cluster.assign(selectedSlots.get(i), topology.getId(), executorList);
                        LOG.info("Assigned to slot {}: {}, {}",selectedSlots.get(i), executorList, topology.getId());
                    }
                }
            } catch (Exception e) {
                LOG.error("Failed to assign topology: {} due to error", topologyName, e);
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
     * 클러스터에서 Supervisor별로 어떤 Group의 Topology들이 실행중인지 매핑 정보를 반환
     *
     * 동일 그룹 Topology가 이미 특정 Supervisor에서 실행 중인 경우
     * 새로운 Topology를 해당 Supervisor에 배치하지 않도록 판단 근거로 활용
     * @param cluster
     * @param topologies
     * @return
     */
    private Map<String, Set<String>> getSupervisorToGroups (Cluster cluster, Topologies topologies) {
        // Supervisor ID를 key로, 해당 Supervisor에서 실행 중인 Group 목록(Set) 을 value로 담는 Map
        Map<String, Set<String>> supervisorToGroups = new HashMap<>();

       for (TopologyDetails topologyDetails : topologies.getTopologies()) { // 현재 Cluster에 존재하는 모든 Topology (실행,대기 포함)
           SchedulerAssignment assignment = cluster.getAssignmentById(topologyDetails.getId()); // 해당 Topology가 실제로 어떤 Supervisor와 WorkerSlot에 할당되어 있는지 조회
           if(assignment == null) { // 아직 스케줄링이 안된 Topology는 건너뜀
               LOG.info("[SKIP] Topology {} is not yet scheduled, skipping for supervisor-group mapping.", topologyDetails.getName());
               continue;
           }
           String group = extractGroup(topologyDetails.getName());

           for (WorkerSlot slot : assignment.getExecutorToSlot().values()) { // Executor별로 할당된 WorkerSlot 정보 반환
               String supervisorId = slot.getNodeId(); //Slot 단위로 어떤 Supervisor에서 실행 중인지 확인
               supervisorToGroups
                       .computeIfAbsent(supervisorId, k -> new HashSet<>())
                       .add(group);
           }
       }
       return supervisorToGroups;
    }
}
