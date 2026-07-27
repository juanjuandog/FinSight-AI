package com.finsight.domain;

import com.finsight.domain.model.Company;
import com.finsight.domain.model.FinancialDocument;
import com.finsight.domain.model.FinancialStatement;
import com.finsight.domain.repository.CompanyRepository;
import com.finsight.domain.repository.DocumentRepository;
import com.finsight.domain.repository.FinancialStatementRepository;
import com.finsight.workflow.WorkflowTask;
import com.finsight.workflow.WorkflowTaskRepository;
import com.finsight.workflow.AgentWorkflowStage;
import com.finsight.workflow.WorkflowStatus;
import com.finsight.workflow.WorkflowTaskType;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public abstract class FinancialDataIngestionTemplate implements FinancialDataSource {
    private final CompanyRepository companyRepository;
    private final DocumentRepository documentRepository;
    private final FinancialStatementRepository statementRepository;
    private final WorkflowTaskRepository taskRepository;

    protected FinancialDataIngestionTemplate(
            CompanyRepository companyRepository,
            DocumentRepository documentRepository,
            FinancialStatementRepository statementRepository,
            WorkflowTaskRepository taskRepository
    ) {
        this.companyRepository = companyRepository;
        this.documentRepository = documentRepository;
        this.statementRepository = statementRepository;
        this.taskRepository = taskRepository;
    }

    public IngestionResult ingestCompany(String companySymbol) {
        String idempotencyKey = ingestionIdempotencyKey(companySymbol);
        if (taskRepository.existsByIdempotencyKey(idempotencyKey)) {
            return new IngestionResult(sourceName(), companySymbol, 0, 0, true);
        }

        WorkflowTask task = taskRepository.createIfAbsent(WorkflowTask.created(
                WorkflowTaskType.FINANCIAL_DATA_INGESTION,
                idempotencyKey,
                Map.of("source", sourceName(), "companySymbol", companySymbol)
        ));

        return executeIngestionTask(task);
    }

    public WorkflowTask createIngestionTask(String companySymbol) {
        String idempotencyKey = ingestionIdempotencyKey(companySymbol);
        return taskRepository.findByIdempotencyKey(idempotencyKey)
                .orElseGet(() -> taskRepository.createIfAbsent(WorkflowTask.created(
                        WorkflowTaskType.FINANCIAL_DATA_INGESTION,
                        idempotencyKey,
                        Map.of("source", sourceName(), "companySymbol", companySymbol)
                )));
    }

    public IngestionResult executeIngestionTask(WorkflowTask task) {
        String companySymbol = String.valueOf(task.payload().get("companySymbol"));
        WorkflowTask runningTask = task.status() == WorkflowStatus.RUNNING
                ? task.atStage(AgentWorkflowStage.INGESTING_DATA)
                : task.running();
        try {
            if (taskRepository.saveIfOwned(runningTask, task.status(), task.fencingToken()).isEmpty()) {
                throw new IllegalStateException("Workflow task ownership was lost before ingestion: " + task.id());
            }
            List<Company> companies = fetchCompanies();
            companies.forEach(companyRepository::save);
            if (companies.stream().noneMatch(company -> company.symbol().equals(companySymbol))
                    && companyRepository.findBySymbol(companySymbol).isEmpty()) {
                companyRepository.save(new Company(companySymbol, "股票 " + companySymbol, "CN", "综合"));
            }
            List<FinancialDocument> documents = fetchDocuments(companySymbol);
            documents.stream().filter(this::isValidDocument).forEach(documentRepository::save);
            List<FinancialStatement> statements = fetchStatements(companySymbol);
            statements.forEach(statementRepository::save);
            taskRepository.saveIfOwned(
                    runningTask.succeeded(),
                    WorkflowStatus.RUNNING,
                    runningTask.fencingToken()
            );
            return new IngestionResult(sourceName(), companySymbol, documents.size(), statements.size(), false);
        } catch (RuntimeException ex) {
            taskRepository.saveIfOwned(
                    runningTask.failed(ex.getMessage()),
                    WorkflowStatus.RUNNING,
                    runningTask.fencingToken()
            );
            throw ex;
        }
    }

    protected boolean isValidDocument(FinancialDocument document) {
        return document.id() != null
                && document.companySymbol() != null
                && document.content() != null
                && !document.content().isBlank();
    }

    private String ingestionIdempotencyKey(String companySymbol) {
        return sourceName() + ":" + companySymbol + ":" + LocalDate.now();
    }

    public record IngestionResult(
            String source,
            String companySymbol,
            int documentCount,
            int statementCount,
            boolean duplicated
    ) {
    }
}
