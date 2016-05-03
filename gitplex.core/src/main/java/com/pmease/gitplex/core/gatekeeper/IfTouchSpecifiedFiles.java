package com.pmease.gitplex.core.gatekeeper;

import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.validator.constraints.NotEmpty;

import com.google.common.collect.Lists;
import com.pmease.commons.util.match.PatternMatcher;
import com.pmease.commons.wicket.editable.annotation.Editable;
import com.pmease.gitplex.core.annotation.PathMatch;
import com.pmease.gitplex.core.entity.Account;
import com.pmease.gitplex.core.entity.Depot;
import com.pmease.gitplex.core.entity.PullRequest;
import com.pmease.gitplex.core.entity.PullRequestUpdate;
import com.pmease.gitplex.core.gatekeeper.checkresult.CheckResult;
import com.pmease.gitplex.core.util.ChildAwareMatcher;
import com.pmease.gitplex.core.util.ParentAwareMatcher;
import com.pmease.gitplex.core.util.includeexclude.IncludeExcludeUtils;

@Editable(order=300, icon="fa-file-text", description=
		"This gate keeper will be passed if specified files are modified")
public class IfTouchSpecifiedFiles extends AbstractGateKeeper {

	private static final long serialVersionUID = 1L;
	
	private static final PatternMatcher INCLUDE_MATCHER = new ParentAwareMatcher();
	
	private static final PatternMatcher EXCLUDE_MATCHER = new ChildAwareMatcher();
	
	private String pathMatch;
	
	@Editable(name="Files to Match")
	@PathMatch
	@NotEmpty
	public String getPathMatch() {
		return pathMatch;
	}

	public void setPathMatch(String pathMatch) {
		this.pathMatch = pathMatch;
	}

	private boolean matches(String value) {
		return IncludeExcludeUtils.getIncludeExclude(pathMatch).matches(INCLUDE_MATCHER, EXCLUDE_MATCHER, value);
	}
	
	@Override
	public CheckResult doCheckRequest(PullRequest request) {
		for (PullRequestUpdate update: request.getEffectiveUpdates()) {
			for (String file: update.getChangedFiles()) {
				if (matches(file)) {
					request.setReferentialUpdate(update);
					return passed(Lists.newArrayList("Touched files matches '" + pathMatch + "'"));
				}
			}
		}

		return failed(Lists.newArrayList("No touched files match '" + pathMatch + "'"));
	}

	@Override
	protected CheckResult doCheckFile(Account user, Depot depot, String branch, String file) {
		if (file != null) {
			if (matches(file)) 
				return passed(Lists.newArrayList("Touched files match '" + pathMatch + "'"));
			else
				return failed(Lists.newArrayList("No touched files match '" + pathMatch + "'"));
		} else {
			return ignored();
		}
	}
	
	@Override
	protected CheckResult doCheckPush(Account user, Depot depot, String refName, ObjectId oldCommit, ObjectId newCommit) {
		if (!oldCommit.equals(ObjectId.zeroId()) && !newCommit.equals(ObjectId.zeroId())) {
			for (String file: depot.git().listChangedFiles(oldCommit.name(), newCommit.name(), null)) {
				if (matches(file))
					return passed(Lists.newArrayList("Touched files match '" + pathMatch + "'"));
			}
			
			return failed(Lists.newArrayList("No touched files match '" + pathMatch + "'"));
		} else {
			return ignored();
		}
	}

}