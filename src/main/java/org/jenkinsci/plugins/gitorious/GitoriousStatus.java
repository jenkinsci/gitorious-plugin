package org.jenkinsci.plugins.gitorious;

import com.google.common.collect.Sets;
import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Hudson;
import hudson.model.UnprotectedRootAction;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitStatus;
import hudson.plugins.git.MultipleScmResolver;
import hudson.plugins.git.browser.GitRepositoryBrowser;
import hudson.plugins.git.browser.GitoriousWeb;
import hudson.scm.SCM;
import hudson.security.ACL;
import hudson.triggers.SCMTrigger;
import hudson.triggers.Trigger;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;

import javax.inject.Inject;
import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

/**
 * @author Kohsuke Kawaguchi
 */
@Extension
public class GitoriousStatus implements UnprotectedRootAction {

    private static final Logger LOGGER = Logger.getLogger(GitoriousStatus.class.getName());

    @Inject
    GitStatus gitStatus;

    public String getDisplayName() {
        return null;
    }

    public String getIconFileName() {
        return null;
    }

    public String getUrlName() {
        return "gitorious";
    }

    /*
        Sample Payload

        {
            "after": "ea1bb8447464a2fbc6dfa48867eb0f462ac3aad1",
            "before": "4c6331eac279eccd9401491fb8df7c61358590c0",
            "commits": [
                {
                    "author": { "email": "smoyer@example.com", "name": "Scott Moyer" },
                    "committed_at": "2012-09-28T03:15:00+00:00",
                    "id": "ea1bb8447464a2fbc6dfa48867eb0f462ac3aad1",
                    "message": "webhook",
                    "timestamp": "2012-09-28T03:15:00+00:00",
                    "url": "https://gitorious.example.com/provisioning/mp-chef/commit/ea1bb8447464a2fbc6dfa48867eb0f462ac3aad1"
                }
            ],
            "project": {
                "description": "Puppet/Chef, rundeck, and anything else associated with provisioning",
                "name": "provisioning"
            },
            "pushed_at": "2012-09-27T23:15:05-04:00",
            "pushed_by": "smoyer",
            "ref": "master",
            "repository": {
                "clones": 1,
                "description": "All things Chef",
                "name": "mp-chef",
                "owner": { "name": "smoyer" },
                "url": "https://gitorious.example.com/provisioning/mp-chef"
            }
        }
     */
    public HttpResponse doNotifyCommit(@QueryParameter String payload) throws ServletException, IOException {
        LOGGER.finer(String.format("Payload:\r\n%s", payload));
        JSONObject jsonObject = JSONObject.fromObject(payload);
        final String baseUrl = jsonObject.getJSONObject("repository").getString("url");
        LOGGER.log(Level.FINE, "Payload Git Repository: {0}", baseUrl);
        final Set<AbstractProject<?, ?>> triggeredProjects = new HashSet<AbstractProject<?, ?>>();
        //Projects that were found, but not configured for polling.
        final Set<AbstractProject<?, ?>> nonPollingProjects = new HashSet<AbstractProject<?, ?>>();

        // HTTP clone access for gitorious is done by adding "git." to the front of the hostname,
        // but the payload URL doesn't come that way. So I added a check in case someone is using HTTP access in jenkins.
        String url2 = baseUrl.replace("://", "://git.");
        LOGGER.log(Level.FINE, "Testing {0}", url2);
        final HttpResponse gitUrlResponse = gitStatus.doNotifyCommit(url2 + ".git", null);
        String url = baseUrl;
        LOGGER.log(Level.FINE, "Testing {0}", url);
        final HttpResponse baseUrlResponse = gitStatus.doNotifyCommit(url + ".git", null);

        String browserFormatedUrl = baseUrl;
        //Jenkins repository browser appends the trailing '/' to the url.
        if (!browserFormatedUrl.endsWith("/")) {
            browserFormatedUrl = browserFormatedUrl + "/";
        }
        LOGGER.log(Level.FINE, "Browser formated Git Repository: {0}", browserFormatedUrl);
        //Check all projects to see if they are configured to use the gitorious browser
        LOGGER.fine("Checking repository browsers for matches.");

        SecurityContext old = ACL.impersonate(ACL.SYSTEM);

        try {
            final List<AbstractProject> projects = Hudson.getInstance().getItems(AbstractProject.class);
            LOGGER.log(Level.FINE, "Number of projects: {0}", projects.size());
            for (AbstractProject<?, ?> project : projects) {
                LOGGER.log(Level.FINE, "Checking project: {0}", project.getFullDisplayName());

                if (project.isDisabled()) {
                    continue;
                }
                Collection<GitSCM> gitSCMs = getProjectScms(project);
                LOGGER.log(Level.FINER, "Number of Git SCMs: {0}", gitSCMs.size());
                for (GitSCM g : gitSCMs) {
                    GitRepositoryBrowser browser = g.getBrowser();
                    if (browser != null && browser instanceof GitoriousWeb) {
                        GitoriousWeb gitoriousBrowser = (GitoriousWeb) browser;
                        String gitoriousBrowserUrl = gitoriousBrowser.getUrl().toString();
                        LOGGER.log(Level.FINER, "Gitorious Browser URL: {0}", gitoriousBrowserUrl);
                        if (gitoriousBrowserUrl.equals(browserFormatedUrl)) {
                            LOGGER.log(Level.FINEST, "URL's match. Attempting to trigger...");
                            //This browser is attached to a project
                            //The following only works if polling is enabled.
                            Trigger t = project.getTrigger(SCMTrigger.class);
                            if (t != null) {
                                LOGGER.log(Level.INFO, "Triggering the polling of \"{0}\"", project.getFullDisplayName());
                                triggeredProjects.add(project);
                                t.run();
                            } else {
                                LOGGER.log(Level.INFO, "Polling not enabled for \"{0}\"", project.getFullDisplayName());
                                nonPollingProjects.add(project);
                            }
                        }
                    }
                }
            }
            return new HttpResponse() {
                public void generateResponse(StaplerRequest req, StaplerResponse rsp, Object node) throws IOException, ServletException {
                    gitUrlResponse.generateResponse(req, rsp, node);
                    baseUrlResponse.generateResponse(req, rsp, node);

                    rsp.setStatus(SC_OK);
                    rsp.setContentType("text/plain");

                    PrintWriter w = rsp.getWriter();
                    for (AbstractProject<?, ?> p : triggeredProjects) {
                        rsp.addHeader(TRIGGERED_HEADER, p.getFullDisplayName());
                    }
                    for (AbstractProject<?, ?> p : triggeredProjects) {
                        w.println(String.format("Scheduled polling of %s\r\n", p.getFullDisplayName()));
                    }
                    for (AbstractProject<?, ?> p : nonPollingProjects) {
                        w.println(String.format("Found \"%s\" but it is not configured for polling.", p.getFullDisplayName()));
                    }
                }

            };
        } finally {
            SecurityContextHolder.setContext(old);
        }
    }
    public static final String TRIGGERED_HEADER = "Triggered";

    //Copied from GitStatus, it is private there.
    private Collection<GitSCM> getProjectScms(AbstractProject<?, ?> project) {
        Set<GitSCM> projectScms = Sets.newHashSet();
        if (Jenkins.getInstance().getPlugin("multiple-scms") != null) {
            MultipleScmResolver multipleScmResolver = new MultipleScmResolver();
            multipleScmResolver.resolveMultiScmIfConfigured(project, projectScms);
        }
        if (projectScms.isEmpty()) {
            SCM scm = project.getScm();
            if (scm instanceof GitSCM) {
                projectScms.add(((GitSCM) scm));
            }
        }
        return projectScms;
    }
}
