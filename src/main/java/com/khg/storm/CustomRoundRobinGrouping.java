package com.khg.storm;

import org.apache.storm.generated.GlobalStreamId;
import org.apache.storm.grouping.CustomStreamGrouping;
import org.apache.storm.task.WorkerTopologyContext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RoundRobin으로 Spout → Bolt 메시지 강제 분산
 * - Storm 기본 Local 우선 전송 로직 무시
 * - 모든 Bolt Task ID를 순서대로 돌아가면서 전송
 */
public class CustomRoundRobinGrouping implements CustomStreamGrouping, Serializable {

    private List<Integer> targetTasks;  // Bolt Task ID 목록
    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    public void prepare(WorkerTopologyContext context, GlobalStreamId stream, List<Integer> targetTasks) {
        // Bolt Task ID 목록을 Storm으로부터 전달받음
        this.targetTasks = new ArrayList<>(targetTasks);
    }

    @Override
    public List<Integer> chooseTasks(int taskId, List<Object> values) {
        // RoundRobin 방식으로 하나의 Task ID를 선택
        int i = Math.abs(index.getAndIncrement() % targetTasks.size());
        List<Integer> bolt = new ArrayList<>(1);
        bolt.add(targetTasks.get(i));
        return bolt;
    }
}
