package kr.uracle.ums.fpc.core;


import kr.uracle.ums.fpc.enums.PATH_KIND;
import kr.uracle.ums.fpc.enums.PitcherState;
import kr.uracle.ums.fpc.tps.TpsManager;
import kr.uracle.ums.fpc.vo.config.AlarmConfigVo;
import kr.uracle.ums.fpc.vo.config.ModuleConfigaVo;
import kr.uracle.ums.fpc.vo.config.PitcherConfigVo;
import kr.uracle.ums.fpc.vo.config.RootConfigVo;
import kr.uracle.ums.fpc.vo.module.HistoryVo;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class Pitcher extends Thread{
	
	private final Map<PATH_KIND, Path> PATH_MAP = new HashMap<PATH_KIND, Path>(10);
	
	private final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
	private final Logger ERROR_LOGGER = LoggerFactory.getLogger("ERROR");
	
	private PitcherState state = PitcherState.READY;

	private boolean isRun = true;
	private boolean isMaster = false;
	
	private final PitcherConfigVo PITCHER_CONFIG;
	private final AlarmConfigVo ALARM_CONFIG;

	private final String PATTERN_OF_DATE;

	private final String SERVER_ID;
	private final String DBMS_ID;

	private final int MAX_THREAD;
	private long CYCLE_TIME = 30*1000;
	private final int MAX_ERROR_COUNT = 10;
	
	private long startTime = 0;
	private long leadTime =0;

	private Detect detect = null;
	private Filter filter = null;

	private ModuleExecutorPool pool = null;
	
	public Pitcher(String name, RootConfigVo rootConfigBean, PitcherConfigVo config) {
		setName(name);
		this.isMaster = rootConfigBean.getDUPLEX().isMASTER();
		this.ALARM_CONFIG = rootConfigBean.getALARM();
		this.PITCHER_CONFIG = config;

		PATTERN_OF_DATE = config.getSAVE_DIRECTORY();
		MAX_THREAD = config.getMAX_THREAD() <=0 ? 5: config.getMAX_THREAD();
		SERVER_ID = getHostName();
		DBMS_ID = config.getDBMS_ID();
	}

	public boolean initailize(){
		// ?????? ?????? ??????
		if(PITCHER_CONFIG.getCYCLE() != null && PITCHER_CONFIG.getCYCLE() > 0) {
			CYCLE_TIME = PITCHER_CONFIG.getCYCLE();
		}
		
		PATH_MAP.put(PATH_KIND.DETECT, Paths.get(PITCHER_CONFIG.getDETECT_PATH()));
		PATH_MAP.put(PATH_KIND.PROCESS, Paths.get(PITCHER_CONFIG.getPROCESS_PATH()));
		PATH_MAP.put(PATH_KIND.SUCCESS, Paths.get(PITCHER_CONFIG.getSUCCESS_PATH()));
		PATH_MAP.put(PATH_KIND.ERROR, Paths.get(PITCHER_CONFIG.getERROR_PATH()));

		// DETECT ??????
		ModuleConfigaVo detectConfig = PITCHER_CONFIG.getDETECTION();
		if(ObjectUtils.isEmpty(detectConfig) || StringUtils.isBlank(detectConfig.getCLASS_NAME())) {
			LOGGER.error("DETECTION_CLASS ????????? ?????????");
			return false;
		}
		detectConfig.setPATH_MAP(PATH_MAP);
		detect = generateModule(detectConfig, ALARM_CONFIG, Detect.class);
		if(detect == null) return false;
		try {
			if(detect.initialize() == false)return false;
		}catch(Exception e) {
			LOGGER.error("["+detect.getPROCESS_NAME()+"] DETECTOR ????????? ??? ?????? ??????", e);
			return false;
		}
		LOGGER.info("[{}] DETECTOR ????????? ??????:", detect.getPROCESS_NAME());
		
		// ?????? ??????
		ModuleConfigaVo filterConfig = PITCHER_CONFIG.getFILTER();
		if(ObjectUtils.isNotEmpty(filterConfig) && StringUtils.isNotBlank(filterConfig.getCLASS_NAME())) {
			filterConfig.setPATH_MAP(PATH_MAP);
			filterConfig.setSERVER_ID(SERVER_ID);
			filterConfig.setDBMS_ID(DBMS_ID);
			filter = generateModule(filterConfig, ALARM_CONFIG,  Filter.class);
			if(filter == null) return false;
			try {
				if(filter.initialize() == false)return false;
			}catch(Exception e) {
				LOGGER.error("["+filter.getPROCESS_NAME()+"] FILTER ????????? ??? ?????? ??????",  e);
				return false;
			}
			LOGGER.info("[{}] FILTER ????????? ??????:", filter.getPROCESS_NAME());
		}
		
		boolean isOk = false;
		Map<String, ModuleConfigaVo> configaVoMap = new HashMap<String, ModuleConfigaVo>(3);
		// ???????????? ??????
		final ModuleConfigaVo preConfig = PITCHER_CONFIG.getPREMODULE();
		if(ObjectUtils.isNotEmpty(preConfig) && StringUtils.isNotBlank(preConfig.getCLASS_NAME())) {
			preConfig.setPATH_MAP(PATH_MAP);
			preConfig.setSERVER_ID(SERVER_ID);
			preConfig.setDBMS_ID(DBMS_ID);
			configaVoMap.put("PRE", preConfig);
		}
		
		// ???????????? ??????
		final ModuleConfigaVo mainConfig = PITCHER_CONFIG.getMAINMODULE();
		if(ObjectUtils.isNotEmpty(mainConfig) && StringUtils.isNotBlank(mainConfig.getCLASS_NAME())) {
			mainConfig.setPATH_MAP(PATH_MAP);
			mainConfig.setSERVER_ID(SERVER_ID);
			mainConfig.setDBMS_ID(DBMS_ID);
			configaVoMap.put("MAIN", mainConfig);
		}
		
		// ???????????? ??????
		final ModuleConfigaVo postConfig = PITCHER_CONFIG.getPOSTMODULE();
		if(ObjectUtils.isNotEmpty(postConfig) && StringUtils.isNotBlank(postConfig.getCLASS_NAME())) {
			postConfig.setPATH_MAP(PATH_MAP);
			postConfig.setSERVER_ID(SERVER_ID);
			postConfig.setDBMS_ID(DBMS_ID);
			configaVoMap.put("POST", postConfig);
		}

		pool = new ModuleExecutorPool(SERVER_ID, DBMS_ID, getName(), MAX_THREAD, configaVoMap, ALARM_CONFIG);
		try {
			pool.initialize();
		} catch (Exception e) {
			LOGGER.error("?????? ????????? ??? ?????? ??????:"+e.getMessage(), e);
			return false;
		}
		return true;
	}
	
	public void close() {
		isRun = false;
		if(pool != null)pool.closeAll();
	}
	
	public void run() {
		pool.execute();
		int errorCnt = 1;
		startTime = 0;
		Path  TARGET_PATH= PATH_MAP.get(PATH_KIND.DETECT);
		while(isRun) {
			state = PitcherState.STANDBY;
			if(errorCnt > MAX_ERROR_COUNT) errorCnt = MAX_ERROR_COUNT;

			try {
				sleep(CYCLE_TIME * errorCnt);
			} catch (InterruptedException e) {
				LOGGER.info("?????? ??? ??????",e);
				e.printStackTrace();
			}

			// ???????????? ????????? ??????, ???????????? ????????? ????????? ???????????? ?????? ??? ?????????????????? ???????????? ?????? ???
			if(isMaster == false) {
				LOGGER.info("?????? ??????????????? {} ?????? ??????", CYCLE_TIME* errorCnt);
				continue;
			}
			if(createDirectory() == false) {
				continue;
			}
			
			// ?????? ?????? ?????? - ??????????????? Hang ?????? ????????? ?????? ???????????? 
			startTime = System.currentTimeMillis();
			state = PitcherState.DETECTION;
			
			List<Path> pathList = detect.detect(TARGET_PATH);
			// ?????? ??? ?????? ??????
			if(pathList == null ) {
				errorCnt +=1;
				LOGGER.debug("?????? ?????? ??? ?????? ???????????? {} ?????? ??????", CYCLE_TIME * errorCnt);
				state = PitcherState.DONE;
				continue;
			}
			
			// ?????? ????????? ????????? ??????
			if(pathList.size() <= 0) {
				LOGGER.debug("{} ????????? ?????? ?????? ??????, {} ?????? ??????", TARGET_PATH, CYCLE_TIME * errorCnt);
				//?????? ?????? ?????????
				errorCnt = 1;
				leadTime = System.currentTimeMillis() - startTime;
				state = PitcherState.DONE;
				continue;
			}

			String yyyyMMdd = StringUtils.isNotBlank(PATTERN_OF_DATE)?LocalDateTime.now().format(DateTimeFormatter.ofPattern(PATTERN_OF_DATE))+ File.separator: null;

			// ?????? ?????? ??????
			if(filter != null) {
				state = PitcherState.FILTERING;
				boolean isOk  = filter.filtering(pathList, yyyyMMdd);
				if(isOk == false) {
					errorCnt +=1;
					LOGGER.info("?????? ?????? ??? ?????? ???????????? {} ?????? ??????", CYCLE_TIME * errorCnt);
					continue;
				}
			}
			
			int totalCnt = pathList.size();			
			if(totalCnt <= 0) {
				LOGGER.debug("?????? ??? {} ?????? ?????? ??????, {} ?????? ??????", TARGET_PATH, CYCLE_TIME);
				errorCnt = 1;
				leadTime = System.currentTimeMillis() - startTime;
				state = PitcherState.DONE;
				continue;
			}
			TpsManager.getInstance().addInputCnt(totalCnt);

			while(pathList.size() > 0) {
				ModuleExecutor executor = pool.getExecutor();
				if(executor == null){
					final long waitForfree = 100;
					try {
						LOGGER.debug("?????? ?????? ???????????? ??????:{}ms", waitForfree);
						sleep(waitForfree);
					} catch (InterruptedException e) {
						LOGGER.info("?????? ?????? ?????? ?????? ??? ??????", e);
					}
					continue;
				}

				TpsManager.getInstance().addProcessCnt(1);
				Path p = pathList.get(0);
				boolean isOk = executor.putWork(p, yyyyMMdd);
				if(isOk)pathList.remove(p);
			}

			//?????? ?????? ?????????
			errorCnt = 1;
			leadTime = System.currentTimeMillis() - startTime;
			LOGGER.info("?????? ?????? ??????, ?????? ??????:{}???, ?????? ??????:{}ms", totalCnt, leadTime);
			state = PitcherState.DONE;
		}
	}

	private <T> T generateModule(ModuleConfigaVo modulConfig, AlarmConfigVo alarmConfig, Class<T> clazz) {
		try {
			Class<?> targetClass = Class.forName(modulConfig.getCLASS_NAME());
			Constructor<?> ctor = targetClass.getDeclaredConstructor(ModuleConfigaVo.class, AlarmConfigVo.class);
			return clazz.cast(ctor.newInstance(modulConfig, alarmConfig));
		} catch (Exception e) {
			LOGGER.error( modulConfig.getCLASS_NAME()+" ?????? ??? ?????? ??????", e);
			e.printStackTrace();
			return null;
		}
	}
		
	public void changeMaster(boolean isMaster) { this.isMaster = isMaster;	}
	
	public long getStartTime() { return startTime; }
	
	public long getLeadTime() { return leadTime;}
	
	public PitcherState getPitcherState() { return state; }
	
	
	private boolean createDirectory() {
		for(Entry<PATH_KIND, Path> element : PATH_MAP.entrySet()) {
			Path p = element.getValue();
			try {
				if(Files.isDirectory(p) == false) Files.createDirectories(p);
			} catch (IOException e) {
				LOGGER.error(p+" ???????????? ?????? ??? ?????? ??????", e);
				return false;
			}
		}
		return true;
	}
	
	private String getHostName() {
		String hostname = null;
		hostname = System.getenv("COMPUTERNAME");
		if(hostname != null) return hostname;
		hostname = System.getenv("HOSTNAME");
		if(hostname != null) return hostname;
		try {
			hostname = InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			LOGGER.info("HOST NAME ?????? ??? ?????? ??????",e);
		}
		if(hostname != null) return hostname;
		return "UnkownHost:"+System.currentTimeMillis();
	}
	
}
