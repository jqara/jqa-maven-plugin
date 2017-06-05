package com.buschmais.jqassistant.scm.maven;

import java.io.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.buschmais.jqassistant.core.analysis.api.Analyzer;
import com.buschmais.jqassistant.core.analysis.api.AnalyzerConfiguration;
import com.buschmais.jqassistant.core.analysis.api.rule.RuleException;
import com.buschmais.jqassistant.core.analysis.api.rule.RuleSelection;
import com.buschmais.jqassistant.core.analysis.api.rule.RuleSet;
import com.buschmais.jqassistant.core.analysis.api.rule.Severity;
import com.buschmais.jqassistant.core.analysis.impl.AnalyzerImpl;
import com.buschmais.jqassistant.core.plugin.api.PluginRepositoryException;
import com.buschmais.jqassistant.core.report.api.ReportException;
import com.buschmais.jqassistant.core.report.api.ReportHelper;
import com.buschmais.jqassistant.core.report.api.ReportPlugin;
import com.buschmais.jqassistant.core.report.impl.CompositeReportPlugin;
import com.buschmais.jqassistant.core.report.impl.InMemoryReportWriter;
import com.buschmais.jqassistant.core.report.impl.XmlReportWriter;
import com.buschmais.jqassistant.core.rule.api.executor.RuleExecutorException;
import com.buschmais.jqassistant.core.store.api.Store;
import com.buschmais.jqassistant.scm.maven.report.JUnitReportWriter;

/**
 * Runs analysis according to the defined rules.
 */
@Mojo(name = "analyze", defaultPhase = LifecyclePhase.VERIFY, threadSafe = true,
      configurator = "custom")
public class AnalyzeMojo extends AbstractProjectMojo {

    private Logger logger = LoggerFactory.getLogger(AnalyzeMojo.class);

    /**
     * Defines the supported report types.
     */
    public enum ReportType {
        JQA, JUNIT
    }

    /**
     * The rule parameters to use (optional).
     */
    @Parameter(property = "jqassistant.ruleParameters")
    protected Map<String, String> ruleParameters;

    /**
     * Indicates if the plugin shall fail if a constraint violation is detected.
     */
    @Deprecated
    @Parameter(property = "jqassistant.failOnViolations")
    protected Boolean failOnViolations = null;

    /**
     * If set also execute concepts which have already been applied.
     */
    @Parameter(property = "jqassistant.executeAppliedConcepts")
    protected boolean executeAppliedConcepts = false;

    /**
     * Severity level for constraint violation failure check. Default value is
     * {@code Severity.INFO}
     */
    @Deprecated
    @Parameter(property = "jqassistant.severity")
    protected String severity;

    /**
     * The severity threshold to warn on rule violations.
     */
    @Parameter(property = "jqassistant.warnOnSeverity", defaultValue = "INFO")
    protected Severity warnOnSeverity;

    /**
     * The severity threshold to fail on rule violations, i.e. break the build.
     */
    @Parameter(property = "jqassistant.failOnSeverity", defaultValue = "CRITICAL")
    protected Severity failOnSeverity;

    @Parameter(property = "jqassistant.junitReportDirectory")
    private java.io.File junitReportDirectory;

    @Parameter(property = "jqassistant.reportTypes")
    private List<ReportType> reportTypes;

    @Parameter(property = "jqassistant.reportProperties")
    private Map<String, Object> reportProperties;

    @Override
    protected boolean isResetStoreBeforeExecution() {
        return false;
    }

    @Override
    public void aggregate(MavenProject rootModule, List<MavenProject> projects, Store store) throws MojoExecutionException, MojoFailureException {
        getLog().info("Will fail on violation of constraints with severity '" + failOnSeverity + "'.");
        getLog().info("Executing analysis for '" + rootModule.getName() + "'.");

        RuleSet ruleSet = readRules(rootModule);
        RuleSelection ruleSelection = RuleSelection.Builder.select(ruleSet, groups, constraints, concepts);
        Map<String, ReportPlugin> reportWriters = new HashMap<>();
        if (reportTypes == null || reportTypes.isEmpty()) {
            reportTypes = Collections.singletonList(ReportType.JQA);
        }
        ReportPlugin xmlReportWriter = null;
        for (ReportType reportType : reportTypes) {
            switch (reportType) {
            case JQA:
                Writer xmlReportFileWriter;
                try {
                    xmlReportFileWriter = new OutputStreamWriter(new FileOutputStream(getXmlReportFile(rootModule)), XmlReportWriter.ENCODING);
                } catch (IOException e) {
                    throw new MojoExecutionException("Cannot create XML report file.", e);
                }
                try {
                    xmlReportWriter = new XmlReportWriter(xmlReportFileWriter);
                } catch (ReportException e) {
                    throw new MojoExecutionException("Cannot create XML report file writer.", e);
                }
                break;
            case JUNIT:
                xmlReportWriter = getJunitReportWriter(rootModule);
                break;
            default:
                throw new MojoExecutionException("Unknown report type " + reportType);
            }
        }
        reportWriters.put(XmlReportWriter.TYPE, xmlReportWriter);
        Map<String, Object> properties = reportProperties != null ? reportProperties : Collections.<String, Object> emptyMap();
        Map<String, ReportPlugin> reportPlugins;
        try {
            reportPlugins = pluginRepositoryProvider.getReportPluginRepository().getReportPlugins(properties);
        } catch (PluginRepositoryException e) {
            throw new MojoExecutionException("Cannot get report plugins.", e);
        }
        reportWriters.putAll(reportPlugins);
        CompositeReportPlugin reportWriter = new CompositeReportPlugin(reportWriters);
        InMemoryReportWriter inMemoryReportWriter = new InMemoryReportWriter(reportWriter);
        AnalyzerConfiguration configuration = new AnalyzerConfiguration();
        configuration.setExecuteAppliedConcepts(executeAppliedConcepts);
        Analyzer analyzer = new AnalyzerImpl(configuration, store, inMemoryReportWriter, logger);
        try {
            analyzer.execute(ruleSet, ruleSelection, ruleParameters);
        } catch (RuleExecutorException e) {
            throw new MojoExecutionException("Analysis failed.", e);
        }
        ReportHelper reportHelper = new ReportHelper(logger);
        store.beginTransaction();
        try {
            Severity effectiveFailOnSeverity;
            if (failOnViolations != null) {
                getLog().warn("The parameter 'failOnViolations' is deprecated, please use 'failOnSeverity' instead.");
            }
            if (severity != null) {
                getLog().warn("The parameter 'severity' is deprecated, please use 'failOnSeverity' instead.");
                try {
                    effectiveFailOnSeverity = Severity.fromValue(severity);
                } catch (RuleException e) {
                    throw new MojoExecutionException("Cannot evaluate parameter severity with value " + severity);
                }
            } else {
                effectiveFailOnSeverity = failOnSeverity ;
            }
            int conceptViolations = reportHelper.verifyConceptResults(warnOnSeverity, effectiveFailOnSeverity, inMemoryReportWriter);
            int constraintViolations = reportHelper.verifyConstraintResults(warnOnSeverity, effectiveFailOnSeverity, inMemoryReportWriter);
            if ((failOnViolations == null || Boolean.TRUE.equals(failOnViolations)) && (conceptViolations > 0 || constraintViolations > 0)) {
                throw new MojoFailureException("Violations detected: " + conceptViolations + " concepts, " + constraintViolations + " constraints");
            }
        } finally {
            store.commitTransaction();
        }
    }

    private JUnitReportWriter getJunitReportWriter(MavenProject baseProject) throws MojoExecutionException {
        JUnitReportWriter junitReportWriter;
        if (junitReportDirectory == null) {
            junitReportDirectory = new File(baseProject.getBuild().getDirectory() + "/surefire-reports");
        }
        junitReportDirectory.mkdirs();
        try {
            junitReportWriter = new JUnitReportWriter(junitReportDirectory);
        } catch (ReportException e) {
            throw new MojoExecutionException("Cannot create XML report file writer.", e);
        }
        return junitReportWriter;
    }

    /**
     * Returns the {@link File} to write the XML report to.
     *
     * @return The {@link File} to write the XML report to.
     * @throws MojoExecutionException
     *             If the file cannot be determined.
     */
    private File getXmlReportFile(MavenProject baseProject) throws MojoExecutionException {
        File selectedXmlReportFile = ProjectResolver.getOutputFile(baseProject, xmlReportFile, REPORT_XML);
        selectedXmlReportFile.getParentFile().mkdirs();
        return selectedXmlReportFile;
    }

}
