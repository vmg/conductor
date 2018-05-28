package com.netflix.conductor.grpc.server;

import com.google.protobuf.Empty;
import com.netflix.conductor.common.metadata.workflow.SkipTaskRequest;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.SearchResult;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.WorkflowSummary;
import com.netflix.conductor.core.config.Configuration;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.grpc.SearchPb;
import com.netflix.conductor.proto.RerunWorkflowRequestPb;
import com.netflix.conductor.proto.StartWorkflowRequestPb;
import com.netflix.conductor.proto.WorkflowPb;
import com.netflix.conductor.grpc.WorkflowServiceGrpc;
import com.netflix.conductor.grpc.WorkflowServicePb;
import com.netflix.conductor.service.ExecutionService;
import com.netflix.conductor.service.MetadataService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class WorkflowServiceImpl extends WorkflowServiceGrpc.WorkflowServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(TaskServiceImpl.class);

    private WorkflowExecutor executor;

    private ExecutionService service;

    private MetadataService metadata;

    private int maxSearchSize;

    @Inject
    public WorkflowServiceImpl(WorkflowExecutor executor, ExecutionService service, MetadataService metadata, Configuration config) {
        this.executor = executor;
        this.service = service;
        this.metadata = metadata;
        this.maxSearchSize = config.getIntProperty("workflow.max.search.size", 5_000);
    }

    private WorkflowServicePb.WorkflowId newWorkflowId(String id) {
        return WorkflowServicePb.WorkflowId
                .newBuilder()
                .setWorkflowId(id)
                .build();
    }

    @Override
    public void startWorkflow(StartWorkflowRequestPb.StartWorkflowRequest pbRequest, StreamObserver<WorkflowServicePb.WorkflowId> response) {
        StartWorkflowRequest request = ProtoMapper.fromProto(pbRequest);
        WorkflowDef def = metadata.getWorkflowDef(request.getName(), request.getVersion());
        if(def == null){
            response.onError(Status.NOT_FOUND
                .withDescription("No such workflow found by name="+request.getName())
                .asRuntimeException()
            );
            return;
        }

        try {
            String id = executor.startWorkflow(
                    def.getName(), def.getVersion(), request.getCorrelationId(),
                    request.getInput(), null, request.getTaskToDomain());
            response.onNext(newWorkflowId(id));
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void getWorkflows(WorkflowServicePb.GetWorkflowsRequest req, StreamObserver<WorkflowServicePb.GetWorkflowsResponse> response) {
        final String name = req.getName();
        final boolean includeClosed = req.getIncludeClosed();
        final boolean includeTasks = req.getIncludeTasks();

        WorkflowServicePb.GetWorkflowsResponse.Builder builder = WorkflowServicePb.GetWorkflowsResponse.newBuilder();

        for (String correlationId : req.getCorrelationIdList()) {
            WorkflowServicePb.GetWorkflowsResponse.Workflows.Builder pbWorkflows =
                    WorkflowServicePb.GetWorkflowsResponse.Workflows.newBuilder();
            for (Workflow wf : service.getWorkflowInstances(name, correlationId, includeClosed, includeTasks)) {
                pbWorkflows.addWorkflows(ProtoMapper.toProto(wf));
            }
            builder.putWorkflowsById(correlationId, pbWorkflows.build());
        }

        response.onNext(builder.build());
        response.onCompleted();
    }

    @Override
    public void getWorkflowStatus(WorkflowServicePb.GetWorkflowStatusRequest req, StreamObserver<WorkflowPb.Workflow> response) {
        try {
            Workflow workflow = service.getExecutionStatus(req.getWorkflowId(), req.getIncludeTasks());
            response.onNext(ProtoMapper.toProto(workflow));
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void removeWorkflow(WorkflowServicePb.RemoveWorkflowRequest req, StreamObserver<Empty> response) {
        try {
            service.removeWorkflow(req.getWorkflodId(), req.getArchiveWorkflow());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void getRunningWorkflows(WorkflowServicePb.GetRunningWorkflowsRequest req, StreamObserver<WorkflowServicePb.GetRunningWorkflowsResponse> response) {
        try {
            List<String> workflowIds;

            if (req.getStartTime() != 0 && req.getEndTime() != 0) {
                workflowIds = executor.getWorkflows(req.getName(), req.getVersion(), req.getStartTime(), req.getEndTime());
            } else {
                workflowIds = executor.getRunningWorkflowIds(req.getName());
            }

            response.onNext(
                WorkflowServicePb.GetRunningWorkflowsResponse.newBuilder()
                    .addAllWorkflowIds(workflowIds)
                    .build()
            );
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void decideWorkflow(WorkflowServicePb.WorkflowId req, StreamObserver<Empty> response) {
        try {
            executor.decide(req.getWorkflowId());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void pauseWorkflow(WorkflowServicePb.WorkflowId req, StreamObserver<Empty> response) {
        try {
            executor.pauseWorkflow(req.getWorkflowId());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void resumeWorkflow(WorkflowServicePb.WorkflowId req, StreamObserver<Empty> response) {
        try {
            executor.resumeWorkflow(req.getWorkflowId());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void skipTaskFromWorkflow(WorkflowServicePb.SkipTaskRequest req, StreamObserver<Empty> response) {
        try {
            SkipTaskRequest skipTask = ProtoMapper.fromProto(req.getRequest());
            executor.skipTaskFromWorkflow(req.getWorkflowId(), req.getTaskReferenceName(), skipTask);
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void rerunWorkflow(RerunWorkflowRequestPb.RerunWorkflowRequest req, StreamObserver<WorkflowServicePb.WorkflowId> response) {
        try {
            String id = executor.rerun(ProtoMapper.fromProto(req));
            response.onNext(newWorkflowId(id));
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void restartWorkflow(WorkflowServicePb.WorkflowId req, StreamObserver<Empty> response) {
        try {
            executor.rewind(req.getWorkflowId());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void retryWorkflow(WorkflowServicePb.WorkflowId req, StreamObserver<Empty> response) {
        try {
            executor.retry(req.getWorkflowId());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void resetWorkflowCallbacks(WorkflowServicePb.WorkflowId req, StreamObserver<Empty> response) {
        try {
            executor.resetCallbacksForInProgressTasks(req.getWorkflowId());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    @Override
    public void terminateWorkflow(WorkflowServicePb.TerminateWorkflowRequest req, StreamObserver<Empty> response) {
        try {
            executor.terminateWorkflow(req.getWorkflowId(), req.getReason());
            response.onCompleted();
        } catch (Exception e) {
            response.onError(e);
        }
    }

    private void doSearch(boolean searchByTask, SearchPb.SearchRequest req, StreamObserver<SearchPb.WorkflowSummarySearchResult> response) {
        final int start = req.getStart();
        final int size = (req.getSize() != 0) ? req.getSize() : maxSearchSize;
        final List<String> sort = convertSort(req.getSort());
        final String freeText = req.getFreeText().isEmpty() ? "*" : req.getFreeText();
        final String query = req.getQuery();

        if (size > maxSearchSize) {
            response.onError(
                    Status.INVALID_ARGUMENT
                    .withDescription("Cannot return more than "+maxSearchSize+" results")
                    .asRuntimeException()
            );
            return;
        }

        SearchResult<WorkflowSummary> searchResult;
        if (searchByTask) {
            searchResult = service.searchWorkflowByTasks(query, freeText, start, size, sort);
        } else {
            searchResult = service.search(query, freeText, start, size, sort);
        }

        // TODO
        // response.onNext(ProtoMapper.toProto(searchResult));
        response.onCompleted();
    }

    private List<String> convertSort(String sortStr) {
        List<String> list = new ArrayList<String>();
        if(sortStr != null && sortStr.length() != 0){
            list = Arrays.asList(sortStr.split("\\|"));
        }
        return list;
    }

    @Override
    public void search(SearchPb.SearchRequest request, StreamObserver<SearchPb.WorkflowSummarySearchResult> responseObserver) {
        doSearch(false, request, responseObserver);
    }

    @Override
    public void searchByTasks(SearchPb.SearchRequest request, StreamObserver<SearchPb.WorkflowSummarySearchResult> responseObserver) {
        doSearch(true, request, responseObserver);
    }
}