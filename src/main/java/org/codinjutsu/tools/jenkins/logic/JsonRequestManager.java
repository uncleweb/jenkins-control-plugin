/*
 * Copyright (c) 2012 David Boissier
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codinjutsu.tools.jenkins.logic;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.codinjutsu.tools.jenkins.JenkinsConfiguration;
import org.codinjutsu.tools.jenkins.exception.ConfigurationException;
import org.codinjutsu.tools.jenkins.model.Build;
import org.codinjutsu.tools.jenkins.model.Jenkins;
import org.codinjutsu.tools.jenkins.model.Job;
import org.codinjutsu.tools.jenkins.model.View;
import org.codinjutsu.tools.jenkins.security.SecurityClient;
import org.codinjutsu.tools.jenkins.security.SecurityMode;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class JsonRequestManager implements RequestManager {

    private static final Logger LOG = Logger.getLogger(JsonRequestManager.class);

    private UrlBuilder urlBuilder;
    private SecurityClient securityClient;

    private JenkinsPlateform jenkinsPlateform = JenkinsPlateform.CLASSIC;

    public JsonRequestManager(SecurityClient securityClient) {
        this.urlBuilder = UrlBuilder.json();
        this.securityClient = securityClient;
    }

    public Jenkins loadJenkinsWorkspace(JenkinsConfiguration configuration) {
        URL url = urlBuilder.createJenkinsWorkspaceUrl(configuration);
        String jenkinsWorkspaceData = securityClient.execute(url);

        JSONObject jsonObject = buildJSONObject(jenkinsWorkspaceData);

        Jenkins jenkins = createJenkins(jsonObject, configuration.getServerUrl());
        jenkins.setPrimaryView(createPreferredView(jsonObject));
        jenkins.setViews(createJenkinsViews(jsonObject));

        int jenkinsPort = url.getPort();
        URL viewUrl = urlBuilder.createViewUrl(jenkinsPlateform, jenkins.getPrimaryView().getUrl());
        int viewPort = viewUrl.getPort();

        if (isJenkinsPortSet(jenkinsPort) && jenkinsPort != viewPort) {
            throw new ConfigurationException(String.format("Jenkins Port seems to be incorrect in the Server configuration page. Please fix 'Jenkins URL' at %s/configure", configuration.getServerUrl()));
        }

        return jenkins;
    }

    private boolean isJenkinsPortSet(int jenkinsPort) {
        return jenkinsPort != -1;
    }

    private List<View> createJenkinsViews(JSONObject jsonObject) {
        List<View> views = new ArrayList<View>();

        JSONArray jsonArray = (JSONArray) jsonObject.get(VIEWS);
        for (Object obj : jsonArray) {
            JSONObject jsonObject1 = (JSONObject) obj;
            String viewName = (String) jsonObject1.get(VIEW_NAME);
            String viewUrl = (String) jsonObject1.get(VIEW_URL);
            View view = View.createView(viewName, viewUrl);
            JSONArray jsonArray1 = (JSONArray) jsonObject1.get(VIEWS);
            if (jsonArray1 != null) {
                for (Object obj2 : jsonArray1) {
                    JSONObject subViewElement = (JSONObject) obj2;
                    String subViewName = (String) subViewElement.get(VIEW_NAME);
                    String subViewUrl = (String) subViewElement.get(VIEW_URL);
                    view.addSubView(View.createNestedView(subViewName, subViewUrl));
                }
            }
            views.add(view);
        }

        return views;
    }

    private View createPreferredView(JSONObject jsonObject) {
        JSONObject primaryView = (JSONObject) jsonObject.get(PRIMARY_VIEW);
        if (primaryView != null) {
            String viewName = (String) primaryView.get(VIEW_NAME);
            String viewUrl = (String) primaryView.get(VIEW_URL);
            return View.createView(viewName, viewUrl);
        }
        return null;
    }

    private Jenkins createJenkins(JSONObject jsonObject, String serverUrl) {
//        Element jenkinsElement = doc.getRootElement();
//        if (!ALLOWED_ROOT_TAGS.contains(jenkinsElement.getName())) {
//            throw new ConfigurationException(String.format("The root tag is should be %s. Actual: '%s'", ALLOWED_ROOT_TAGS, jenkinsElement.getName()));
//        }
        String description = (String) jsonObject.get(JENKINS_DESCRIPTION);
        if (description == null) {
            description = "";
        }
        return new Jenkins(description, serverUrl);
    }

    public Map<String, Build> loadJenkinsRssLatestBuilds(JenkinsConfiguration configuration) {
        throw new UnsupportedOperationException();
    }

    public List<Job> loadJenkinsView(String viewUrl) {
        URL url = urlBuilder.createViewUrl(jenkinsPlateform, viewUrl);
        String jenkinsViewData = securityClient.execute(url);
        JSONObject jsonObject = buildJSONObject(jenkinsViewData);
        return jenkinsPlateform.loadViewStrategy.loadJenkinsView(jsonObject);
    }

    public Job loadJob(String jenkinsJobUrl) {
        URL url = urlBuilder.createJobUrl(jenkinsJobUrl);

        String jenkinsJobData = securityClient.execute(url);
        JSONObject jsonObject = buildJSONObject(jenkinsJobData);
        return createJob(jsonObject);
    }

    private static Job createJob(JSONObject jsonObject) {
        String jobName = (String) jsonObject.get(JOB_NAME);
        String jobColor = (String) jsonObject.get(JOB_COLOR);
        String jobUrl = (String) jsonObject.get(JOB_URL);
        Boolean inQueue = (Boolean) jsonObject.get(JOB_IS_IN_QUEUE);
        Boolean buildable = (Boolean) jsonObject.get(JOB_IS_BUILDABLE);

        Job job = Job.createJob(jobName, jobColor, jobUrl, inQueue, buildable);

        Job.Health jobHealth = getJobHealth(jsonObject);
        if (jobHealth != null) {
            job.setHealth(jobHealth);
        }
        JSONObject lastBuild = (JSONObject) jsonObject.get(JOB_LAST_BUILD);
        if (lastBuild != null) {
            job.setLastBuild(createLastBuild(lastBuild));
        }

        JSONArray propertyList = (JSONArray) jsonObject.get(PARAMETER_PROPERTY);
        if (propertyList != null) {
            for (Object property : propertyList) {
                JSONObject jsonProperty = (JSONObject) property;
                JSONArray parameterDefinitions = (JSONArray) jsonProperty.get(PARAMETER_DEFINITIONS);
                if (parameterDefinitions != null && !parameterDefinitions.isEmpty()) {
                    setJobParameters(job, parameterDefinitions);
                }
            }
        }

        return job;
    }

    private static Job.Health getJobHealth(JSONObject jsonObject) {
        String jobHealthLevel = null;
        String jobHealthDescription = null;
        JSONArray jobHealthArray = (JSONArray) jsonObject.get(JOB_HEALTH);
        if (jobHealthArray != null) {
            jobHealthLevel = (String) ((JSONObject) jobHealthArray.get(0)).get(JOB_HEALTH_ICON);
            if (StringUtils.isNotEmpty(jobHealthLevel)) {
                if (jobHealthLevel.endsWith(".png"))
                    jobHealthLevel = jobHealthLevel.substring(0, jobHealthLevel.lastIndexOf(".png"));
                else {
                    jobHealthLevel = jobHealthLevel.substring(0, jobHealthLevel.lastIndexOf(".gif"));
                }
            } else {
                jobHealthLevel = null;
            }

            jobHealthDescription = (String) ((JSONObject) jobHealthArray.get(0)).get(JOB_HEALTH_DESCRIPTION);
        }

        if (!StringUtils.isEmpty(jobHealthLevel)) {
            return Job.Health.createHealth(jobHealthLevel, jobHealthDescription);
        }
        return null;
    }


    private static Build createLastBuild(JSONObject jobLastBuild) {
        Boolean isBuilding = (Boolean) jobLastBuild.get(BUILD_IS_BUILDING);
        String status = (String) jobLastBuild.get(BUILD_RESULT);
        Long number = (Long) jobLastBuild.get(BUILD_NUMBER);
        String buildUrl = (String) jobLastBuild.get(BUILD_URL);
        String date = (String) jobLastBuild.get(BUILD_ID);
        return Build.createBuildFromWorkspace(buildUrl, number, status, isBuilding, date);
    }


    private static void setJobParameters(Job job, JSONArray parameterDefinitions) {

        for (Object jsonParameterDefinition : parameterDefinitions) {
            JSONObject parameterDefinition = (JSONObject) jsonParameterDefinition;

            String paramName = (String) parameterDefinition.get(PARAMETER_NAME);
            String paramType = (String) parameterDefinition.get(PARAMETER_TYPE);

            String defaultParamValue = null;
            JSONObject defaultParamElement = (JSONObject) parameterDefinition.get(PARAMETER_DEFAULT_PARAM);
            if (defaultParamElement != null) {
                defaultParamValue = (String) defaultParamElement.get(PARAMETER_DEFAULT_PARAM_VALUE);
            }
            String[] choices = extractChoices(parameterDefinition);

            job.addParameter(paramName, paramType, defaultParamValue, choices);
        }
    }


    private static String[] extractChoices(JSONObject parameterDefinition) {
        JSONArray choices = (JSONArray) parameterDefinition.get(PARAMETER_CHOICE);
        String[] paramValues = new String[0];
        if (choices != null && !choices.isEmpty()) {
            paramValues = new String[choices.size()];
            for (int i = 0; i < choices.size(); i++) {
                paramValues[i] = (String) choices.get(i);
            }
        }
        return paramValues;
    }

    private JSONObject buildJSONObject(String jenkinsJobData) {
        JSONParser parser = new JSONParser();
        try {
            return (JSONObject) parser.parse(jenkinsJobData);
        } catch (ParseException e) {
            LOG.error("Error during analyzing the Jenkins data.", e);
            throw new RuntimeException("Error during analyzing the Jenkins data.");
        }
    }

    public static List<Job> createJobs(JSONArray jsonArray) {
        List<Job> jobs = new LinkedList<Job>();
        for (Object obj : jsonArray) {
            JSONObject jsonObject = (JSONObject) obj;
            jobs.add(createJob(jsonObject));
        }
        return jobs;
    }

    public void runBuild(Job job, JenkinsConfiguration configuration) {
        throw new UnsupportedOperationException();
    }

    public void runParameterizedBuild(Job job, JenkinsConfiguration configuration, Map<String, String> paramValueMap) {
        throw new UnsupportedOperationException();
    }

    public void authenticate(String serverUrl, SecurityMode securityMode, String username, String passwordFile, String crumbDataFile) {
        throw new UnsupportedOperationException();
    }

    public List<Job> loadFavoriteJobs(List<JenkinsConfiguration.FavoriteJob> favoriteJobs) {
        throw new UnsupportedOperationException();
    }

    public void setJenkinsPlateform(JenkinsPlateform jenkinsPlateform) {
        this.jenkinsPlateform = jenkinsPlateform;
    }
}