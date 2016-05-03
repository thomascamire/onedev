package com.pmease.gitplex.web.page.admin;

import java.io.Serializable;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.ajax.markup.html.form.AjaxButton;
import org.apache.wicket.markup.html.form.Form;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.pmease.commons.wicket.behavior.testform.TestFormBehavior;
import com.pmease.commons.wicket.behavior.testform.TestResult;
import com.pmease.commons.wicket.editable.BeanContext;
import com.pmease.commons.wicket.editable.BeanEditor;
import com.pmease.gitplex.core.GitPlex;
import com.pmease.gitplex.core.entity.Account;
import com.pmease.gitplex.core.manager.AccountManager;
import com.pmease.gitplex.core.manager.ConfigManager;
import com.pmease.gitplex.core.manager.MailManager;
import com.pmease.gitplex.core.setting.MailSetting;

@SuppressWarnings("serial")
public class MailSettingPage extends AdministrationPage {

	private static final Logger logger = LoggerFactory.getLogger(MailSettingPage.class);
	
	private MailSetting mailSetting;
	
	private BeanEditor<Serializable> editor;
	
	@Override
	protected void onInitialize() {
		super.onInitialize();
		
		mailSetting = GitPlex.getInstance(ConfigManager.class).getMailSetting();
		if (mailSetting == null)
			mailSetting = new MailSetting();
		
		Form<?> form = new Form<Void>("form") {

			@Override
			protected void onSubmit() {
				super.onSubmit();
				GitPlex.getInstance(ConfigManager.class).saveMailSetting(mailSetting);
				getSession().success("Mail setting has been updated");
				setResponsePage(MailSettingPage.class);
			}
			
		};
		form.add(editor = BeanContext.editBean("editor", mailSetting));
				
		form.add(new AjaxButton("test") {

			private TestFormBehavior testBehavior;
			
			@Override
			protected void onInitialize() {
				super.onInitialize();
				
				add(testBehavior = new TestFormBehavior() {

					@Override
					protected TestResult test() {
						Account currentUser = GitPlex.getInstance(AccountManager.class).getCurrent();
						try {
							GitPlex.getInstance(MailManager.class).sendMailNow(
									mailSetting, Sets.newHashSet(currentUser), 
									"Test email from GitPlex", "Great, your mail setting is correct!");
							return new TestResult.Successful("Test mail has been sent to " + 
									currentUser.getEmail() + ", please check your mail box.");
						} catch (Exception e) {
							logger.error("Error sending test email", e);
							return new TestResult.Failed("Error sending test email: " + e.getMessage() + ", check server log for details.");
						}
					}
					
				});
			}

			@Override
			protected void onSubmit(AjaxRequestTarget target, Form<?> form) {
				super.onSubmit(target, form);
				
				target.add(editor);
				target.focusComponent(null);
				testBehavior.requestTest(target);
			}

			@Override
			protected void onError(AjaxRequestTarget target, Form<?> form) {
				target.add(editor);
			}

		});
		
		add(form);
	}

}