/*
 * Copyright (C) 2012 - 2012 NHN Corporation
 * All rights reserved.
 *
 * This file is part of The nGrinder software distribution. Refer to
 * the file LICENSE which is part of The nGrinder distribution for
 * licensing details. The nGrinder distribution is available on the
 * Internet at http://nhnopensource.org/ngrinder
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngrinder.agent.service;

import static net.grinder.message.console.AgentControllerState.INACTIVE;
import static net.grinder.message.console.AgentControllerState.WRONG_REGION;
import static org.ngrinder.agent.model.ClustedAgentRequest.RequestType.SHARE_AGENT_SYSTEM_DATA_MODEL;
import static org.ngrinder.agent.model.ClustedAgentRequest.RequestType.STOP_AGENT;
import static org.ngrinder.agent.repository.AgentManagerSpecification.startWithRegion;
import static org.ngrinder.common.util.CollectionUtils.newHashMap;
import static org.ngrinder.common.util.TypeConvertUtil.convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import net.grinder.common.processidentity.AgentIdentity;
import net.grinder.engine.controller.AgentControllerIdentityImplementation;
import net.grinder.util.thread.InterruptibleRunnable;
import net.sf.ehcache.Ehcache;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.ngrinder.agent.model.AgentInfo;
import org.ngrinder.agent.model.ClustedAgentRequest;
import org.ngrinder.infra.logger.CoreLogger;
import org.ngrinder.infra.schedule.ScheduledTask;
import org.ngrinder.model.User;
import org.ngrinder.monitor.controller.model.SystemDataModel;
import org.ngrinder.perftest.service.AgentManager;
import org.ngrinder.region.service.RegionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.Cache.ValueWrapper;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.google.common.collect.Maps;

/**
 * Cluster enabled version of {@link AgentManagerService}.
 * 
 * @author JunHo Yoon
 * @since 3.1
 */
public class ClusteredAgentManagerService extends AgentManagerService {
	private static final Logger LOGGER = LoggerFactory.getLogger(ClusteredAgentManagerService.class);

	@Autowired
	private CacheManager cacheManager;

	private Cache agentRequestCache;

	private Cache agentMonitorCache;

	private Cache agentMonioringTargetsCache;

	@Autowired
	private ScheduledTask scheduledTask;

	@Autowired
	private RegionService regionService;

	/**
	 * Initialize.
	 */
	@PostConstruct
	public void init() {
		agentMonioringTargetsCache = getCacheManager().getCache("agent_monitoring_targets");
		if (getConfig().isCluster()) {
			agentRequestCache = getCacheManager().getCache("agent_request");
			agentMonitorCache = getCacheManager().getCache("agent_monitoring");
			scheduledTask.addScheduledTaskEvery3Sec(new InterruptibleRunnable() {
				@Override
				public void interruptibleRun() {
					List<String> keysWithExpiryCheck = convert(((Ehcache) agentRequestCache.getNativeCache())
									.getKeysWithExpiryCheck());
					String region = getConfig().getRegion() + "|";
					for (String each : keysWithExpiryCheck) {
						try {
							if (each.startsWith(region) && agentRequestCache.get(each) != null) {
								ClustedAgentRequest agentRequest = convert(agentRequestCache.get(each).get());
								AgentControllerIdentityImplementation agentIdentity = getLocalAgentIdentityByIpAndName(
												agentRequest.getAgentIp(), agentRequest.getAgentName());
								if (agentIdentity != null) {
									agentRequest.getRequestType().process(ClusteredAgentManagerService.this,
													agentIdentity);
								}
							}

						} catch (Exception e) {
							CoreLogger.LOGGER.error(e.getMessage(), e);
						}
						agentRequestCache.evict(each);
					}
				}
			});
		}
	}

	/**
	 * Run a scheduled task to check the agent status.
	 * 
	 * @since 3.1
	 */
	public void checkAgentStatus() {
		List<AgentInfo> changeAgents = new ArrayList<AgentInfo>();
		List<AgentInfo> deleteAgents = new ArrayList<AgentInfo>();
		String curRegion = getConfig().getRegion();
		List<String> regions = getRegions();

		Set<AgentIdentity> allAttachedAgents = getAgentManager().getAllAttachedAgents();
		Map<String, AgentControllerIdentityImplementation> attachedAgentMap = newHashMap(allAttachedAgents);
		for (AgentIdentity agentIdentity : allAttachedAgents) {
			AgentControllerIdentityImplementation existingAgent = convert(agentIdentity);
			attachedAgentMap.put(createAgentKey(existingAgent), existingAgent);
		}

		List<AgentInfo> agentsInDB = getAgentRepository().findAll();
		Map<String, AgentInfo> agentsInDBMap = Maps.newHashMap();
		// step1. check all agents in DB, whether they are attached to controller.
		for (AgentInfo eachAgentInDB : agentsInDB) {
			String keyOfAgentInDB = createAgentKey(eachAgentInDB);
			agentsInDBMap.put(keyOfAgentInDB, eachAgentInDB);
			AgentControllerIdentityImplementation agentIdentity = attachedAgentMap.remove(keyOfAgentInDB);
			String regionOfEachAgentInDB = extractRegionFromAgentRegion(eachAgentInDB.getRegion());

			if (agentIdentity != null) {// if the agent attached to current controller
				if (StringUtils.equals(regionOfEachAgentInDB, curRegion)) {
					if (!hasSamePortAndStatus(eachAgentInDB, agentIdentity)) {
						fillUp(eachAgentInDB, agentIdentity);
						changeAgents.add(eachAgentInDB);
					}
				} else if (eachAgentInDB.getStatus() != WRONG_REGION) {
					// the region config is wrong
					eachAgentInDB.setStatus(WRONG_REGION);
					changeAgents.add(eachAgentInDB);

				}
			} else { // the agent in DB is not attached to current controller
				if (StringUtils.equals(regionOfEachAgentInDB, curRegion)) {
					// the agent WAS attached to this controller before, but it is down.
					if (eachAgentInDB.getStatus() != INACTIVE) {
						eachAgentInDB.setStatus(INACTIVE);
						changeAgents.add(eachAgentInDB);
					}
				} else if (!regions.contains(regionOfEachAgentInDB)) {
					// this agent in DB 's region is not in any region
					deleteAgents.add(eachAgentInDB);
				}
			}

		}

		// step2. check all attached agents, whether they are new, and not saved in DB.
		for (AgentControllerIdentityImplementation agentIdentity : attachedAgentMap.values()) {
			AgentInfo newAgentInfo = fillUp(new AgentInfo(), agentIdentity);
			if (!StringUtils.equals(extractRegionFromAgentRegion(agentIdentity.getRegion()), curRegion)) {
				newAgentInfo.setStatus(WRONG_REGION);
			}
			changeAgents.add(newAgentInfo);
		}

		// step3. update into DB
		getAgentRepository().save(changeAgents);
		getAgentRepository().delete(deleteAgents);
	}

	private boolean hasSamePortAndStatus(AgentInfo agentInfo, AgentControllerIdentityImplementation agentIdentity) {
		AgentManager agentManager = getAgentManager();
		return agentInfo.getPort() == agentManager.getAgentConnectingPort(agentIdentity)
						&& agentInfo.getStatus() == agentManager.getAgentState(agentIdentity);
	}

	/**
	 * Collect agent system data every second.
	 * 
	 */
	@Scheduled(fixedDelay = 1000)
	public void collectAgentSystemData() {
		Ehcache nativeCache = (Ehcache) agentMonioringTargetsCache.getNativeCache();
		List<String> keysWithExpiryCheck = convert(nativeCache.getKeysWithExpiryCheck());
		for (String each : keysWithExpiryCheck) {
			ValueWrapper value = agentMonioringTargetsCache.get(each);
			if (value != null && value.get() != null) {
				agentMonitorCache.put(each, getAgentManager().getSystemDataModel((AgentIdentity) value.get()));
			}
		}
	}

	/**
	 * get the available agent count map in all regions of the user, including the free agents and
	 * user specified agents.
	 * 
	 * @param regions
	 *            current region list
	 * @param user
	 *            current user
	 * @return user available agent count map
	 */
	@Override
	@Transactional
	public Map<String, MutableInt> getUserAvailableAgentCountMap(User user) {
		List<String> regions = getRegions();
		Map<String, MutableInt> availShareAgents = newHashMap(regions);
		Map<String, MutableInt> availUserOwnAgent = newHashMap(regions);
		for (String region : regions) {
			availShareAgents.put(region, new MutableInt(0));
			availUserOwnAgent.put(region, new MutableInt(0));
		}
		String myAgentSuffix = "_owned_" + user.getUserId();

		for (AgentInfo agentInfo : getAllActiveAgentInfoFromDB()) {
			// Skip the all agents which doesn't approved, is inactive or
			// doesn't have region
			// prefix.
			if (!agentInfo.isApproved()) {
				continue;
			}

			String fullRegion = agentInfo.getRegion();
			String region = extractRegionFromAgentRegion(fullRegion);
			if (StringUtils.isBlank(region) || !StringUtils.equals(region, getConfig().getRegion())) {
				continue;
			}
			// It's my own agent
			if (fullRegion.endsWith(myAgentSuffix)) {
				incrementAgentCount(availUserOwnAgent, region, user.getUserId());
			} else if (fullRegion.contains("_owned_")) {
				// If it's the others agent.. skip..
				continue;
			} else {
				incrementAgentCount(availShareAgents, region, user.getUserId());
			}
		}

		int maxAgentSizePerConsole = getMaxAgentSizePerConsole();

		for (String region : regions) {
			MutableInt mutableInt = availShareAgents.get(region);
			int shareAgentCount = mutableInt.intValue();
			mutableInt.setValue(Math.min(shareAgentCount, maxAgentSizePerConsole));
			mutableInt.add(availUserOwnAgent.get(region));
		}
		return availShareAgents;
	}

	protected List<String> getRegions() {
		return regionService.getRegions();
	}

	String extractRegionFromAgentRegion(String agentRegion) {
		if (agentRegion.contains("_owned_")) {
			return agentRegion.substring(0, agentRegion.indexOf("_owned_"));
		}
		return agentRegion;
	}

	private void incrementAgentCount(Map<String, MutableInt> agentMap, String region, String userId) {
		if (!agentMap.containsKey(region)) {
			LOGGER.warn("Region :{} not exist in cluster nor owned by user:{}.", region, userId);
		} else {
			agentMap.get(region).increment();
		}
	}

	/**
	 * Get all agents attached of this region from DB.
	 * 
	 * This method is cluster aware. If it's cluster mode it return all agents attached in this
	 * region.
	 * 
	 * @return agent list
	 */
	@Override
	public List<AgentInfo> getLocalAgentListFromDB() {
		return getAgentRepository().findAll(startWithRegion(getConfig().getRegion()));
	}

	/**
	 * Stop agent. In cluster mode, it queues the agent stop request to agentRequestCache.
	 * 
	 * @param id
	 *            agent id in db
	 * 
	 */
	@Override
	public void stopAgent(Long id) {
		AgentInfo agent = getAgent(id, false);
		if (agent == null) {
			return;
		}
		agentRequestCache.put(extractRegionFromAgentRegion(agent.getRegion()) + "|" + createAgentKey(agent),
						new ClustedAgentRequest(agent.getIp(), agent.getName(), STOP_AGENT));
	}

	/**
	 * Add the agent system data model share request on cache.
	 * 
	 * @param id
	 *            agent id in db.
	 */
	@Override
	public void requestShareAgentSystemDataModel(Long id) {
		AgentInfo agent = getAgent(id, false);
		if (agent == null) {
			return;
		}
		agentRequestCache.put(extractRegionFromAgentRegion(agent.getRegion()) + "|" + createAgentKey(agent),
						new ClustedAgentRequest(agent.getIp(), agent.getName(), SHARE_AGENT_SYSTEM_DATA_MODEL));
	}

	/**
	 * Get agent system data model for the given ip. This method is cluster aware.
	 * 
	 * @param ip
	 *            agent ip.
	 * @return {@link SystemDataModel} instance.
	 */
	@Override
	public SystemDataModel getAgentSystemDataModel(String ip, String name) {
		ValueWrapper valueWrapper = agentMonitorCache.get(createAgentKey(ip, name));
		return valueWrapper == null ? new SystemDataModel() : (SystemDataModel) valueWrapper.get();
	}

	/**
	 * Register agent monitoring target. This method should be called in the controller in which the
	 * given agent exists.
	 * 
	 * @param id
	 *            agent id
	 * @param ip
	 *            agent ip
	 * @param agentIdentity
	 *            agent identity
	 */
	public void addAgentMonitoringTarget(AgentControllerIdentityImplementation agentIdentity) {
		agentMonioringTargetsCache.put(createAgentKey(agentIdentity), agentIdentity);
	}

	CacheManager getCacheManager() {
		return cacheManager;
	}

	void setCacheManager(CacheManager cacheManager) {
		this.cacheManager = cacheManager;
	}

	public void stopAgent(AgentControllerIdentityImplementation agentIdentity) {
		getAgentManager().stopAgent(agentIdentity);
	}

}