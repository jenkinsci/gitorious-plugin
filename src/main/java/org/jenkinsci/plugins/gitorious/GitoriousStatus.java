package org.jenkinsci.plugins.gitorious;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.plugins.git.GitStatus;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;

import javax.inject.Inject;
import javax.servlet.ServletException;
import java.io.IOException;

/**
 * @author Kohsuke Kawaguchi
 */
@Extension
public class GitoriousStatus implements UnprotectedRootAction {
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

    public HttpResponse doNotifyCommit(@QueryParameter(required=true) String payload)  throws ServletException, IOException {
    	JSONObject jsonObject = JSONObject.fromObject( payload );

        // HTTP clone access for gitorious is done by adding "git." to the front of the hostname,
        // but the payload URL doesn't come that way. So I added a check in case someone is using HTTP access in jenkins.
        String url2 = jsonObject.getJSONObject("repository").getString("url").replace("https://", "git://");
        String branch = jsonObject.getString("ref");
        return gitStatus.doNotifyCommit(url2 + ".git", branch, null);
    }
}
