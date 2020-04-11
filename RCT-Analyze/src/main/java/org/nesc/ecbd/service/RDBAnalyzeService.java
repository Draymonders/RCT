/**
 * 
 */
package org.nesc.ecbd.service;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.nesc.ecbd.Analyzer;
import org.nesc.ecbd.entity.AnalyzeStatus;
import org.nesc.ecbd.entity.ScheduleInfo;
import org.nesc.ecbd.utils.FileCopyUtil;
import org.nesc.ecbd.utils.Report;
import org.nesc.ecbd.worker.AnalyzerWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSONObject;

/**
 * @author Hulva Luva.H
 * @since 2018年3月30日
 *
 */
@Service
public class RDBAnalyzeService {
	private final static Logger LOG = LoggerFactory.getLogger(RDBAnalyzeService.class);
	@Autowired
	RestTemplate restTemplate;

	private static int serverPort;
	private static ScheduleInfo scheduleInfo;
	private static AnalyzeStatus status;

	private static Map<String, String> rdbPaths = null;
	private static Map<String, String> RDB_ANALYZE_STATUS = new ConcurrentHashMap<String, String>();
	private AnalyzeExecuter executer = null;



	public static int getServerPort() {
		return serverPort;
	}

	public static ScheduleInfo getScheduleInfo() {
		return scheduleInfo;
	}

	public void setScheduleInfo(ScheduleInfo _scheduleInfo) {
		scheduleInfo = _scheduleInfo;
	}

	public static AnalyzeStatus getStatus() {
		return status;
	}

	public static void setStatus(AnalyzeStatus _status) {
		status = _status;
	}

	/**
	 * 检查RDB文件目录是否存在
	 * 
	 * @return
	 */
	public JSONObject doCheck() {
		rdbPaths = new HashMap<String, String>();
		if(RDB_ANALYZE_STATUS.size()!=0) {
			RDB_ANALYZE_STATUS.clear();
		}

		JSONObject ret = new JSONObject();
		boolean allPathValid = true;
		// 检查RDB文件临时存放目录是否存在
		File rdbTemp = new File("rdbtemp");
		if (!rdbTemp.exists()) {
			rdbTemp.mkdir();
		}
		File rdbFile = null;
		String basePath = scheduleInfo.getDataPath().endsWith("/") ? scheduleInfo.getDataPath()
				: scheduleInfo.getDataPath() + "/";
		JSONObject msg = new JSONObject();
		String rdbPath = null;
        for (String port : scheduleInfo.getPorts()) {
			// rdb file root path
			rdbPath = basePath + port;
			rdbFile = findRDBFile(rdbPath);
            if (rdbFile == null) {
                allPathValid = false;
                msg.put(port, "In root path [" +rdbPath+ "] not find rdb file!");
            } else {
                rdbPaths.put(port, rdbFile.getPath());
            }
//			basePath =  scheduleInfo.getDataPath().endsWith("/") ? scheduleInfo.getDataPath()
//					: scheduleInfo.getDataPath() + "/";
        }
		ret.put("checked", allPathValid);
		if (!allPathValid) {
			ret.put("message", msg);
//            setStatus(AnalyzeStatus.ERROR);
			return ret;
		}
		setStatus(AnalyzeStatus.READY);
		return ret;
	}

	/**
	 * 在指定目录(rootPath)下查找第一个以rdb文件
	 * @param rootPath 在此路径下寻找
	 * @return 如果找到rdb文件，返回文件，否则返回空
	 */
	private File findRDBFile(String rootPath){
		File rdbDir = new File(rootPath);
		File[] files = rdbDir.listFiles();
		if(files != null){
			for(File file : files){
				if(file.getPath().endsWith(".rdb")){
					return file;
				}else if(file.isDirectory()){
					File rFile = findRDBFile(file.getPath());
					if(rFile != null){
						return rFile;
					}
				}
			}
		}
		return null;
	}

	/**
	 * 单文件逐个分析
	 */
	public void execute() {
		this.executer = new AnalyzeExecuter();
		Thread executerThread = new Thread(this.executer);
		executerThread.start();
	}

	public JSONObject status() {
		JSONObject ret = new JSONObject();
		try {
			String ip = InetAddress.getLocalHost().getHostAddress();
			ret.put("ip", ip);
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		if (scheduleInfo == null) {
			ret.put("scheduleInfo", "null");
		} else {
			ret.put("scheduleInfo", scheduleInfo);
			JSONObject rdbAnalyzeStatus = new JSONObject();
			if (AnalyzeStatus.CANCELED.equals(status)) {
				for (String port : scheduleInfo.getPorts()) {
					if (RDB_ANALYZE_STATUS.containsKey(port)) {
						rdbAnalyzeStatus.put(port, JSONObject.parseObject(RDB_ANALYZE_STATUS.get(port)));
					} else {
						JSONObject canceled = new JSONObject();
						canceled.put("status", "CANCELED");
						rdbAnalyzeStatus.put(port, canceled);
					}
				}
				ret.put("rdbAnalyzeStatus", rdbAnalyzeStatus);
				return ret;
			}
			if (AnalyzeStatus.RUNNING.equals(status)) {
				JSONObject currentExecuterStatus = this.executer.currentStatus();
				int currentPort = currentExecuterStatus.getIntValue("port");
				currentExecuterStatus.remove("port");
				RDB_ANALYZE_STATUS.put(String.valueOf(currentPort), currentExecuterStatus.toJSONString());
			}

			for (String port : scheduleInfo.getPorts()) {
				if (RDB_ANALYZE_STATUS.containsKey(port)) {
					rdbAnalyzeStatus.put(port, JSONObject.parseObject(RDB_ANALYZE_STATUS.get(port)));
				} else {
					JSONObject notStart = new JSONObject();
					notStart.put("status", "NOT_START");
					rdbAnalyzeStatus.put(port, notStart);
				}
			}
			ret.put("rdbAnalyzeStatus", rdbAnalyzeStatus);
		}

		ret.put("currentCacheSize", AnalyzerWorker.cache.size());
		ret.put("cacheSize", Analyzer.MAX_QUEUE_SIZE);
		return ret;
	}

	public boolean cancel() {
		setStatus(AnalyzeStatus.CANCELED);
		return true;
	}

	class AnalyzeExecuter implements Runnable {
		private SimpleAnalyzerManager excuter = null;
		private File rdbTempFile = null;

		@Override
		public void run() {
			try {
				setStatus(AnalyzeStatus.RUNNING);
				// 启动消费队列进程
				AnalyzerWorker.startWorker();
				for (String port : scheduleInfo.getPorts()) {
					if (AnalyzeStatus.CANCELED.equals(status)) {
						// TODO 在执行中被中断分析进程，貌似没有释放掉相应资源
						break;
					}
					if (this.excuter == null) {
						this.excuter = new SimpleAnalyzerManager(scheduleInfo.getAnalyzerTypes());
						this.excuter.setPort(Integer.parseInt(port));
					} else {
						this.excuter.setPort(Integer.parseInt(port));
						this.excuter.setStatus(AnalyzeStatus.READY);
					}
					// copy rdb file to a temp folder
					this.rdbTempFile = new File("rdbtemp/dump.rdb");

					LOG.info("Start copy rdb file...");
					long start = System.currentTimeMillis();
					FileCopyUtil.copyFileUsingStream(new File(rdbPaths.get(port)), this.rdbTempFile);
					LOG.info("Done copy rdb file, takes {} ms", System.currentTimeMillis() - start);

					this.excuter.execute(this.rdbTempFile.getAbsolutePath());

					RDB_ANALYZE_STATUS.put(port, this.excuter.result().toJSONString());

					Report.reportStatus(status().toJSONString());

					this.excuter.reset();
					System.gc();
				}
				setStatus(AnalyzeStatus.CANCELED.equals(status) ? status : AnalyzeStatus.DONE);
				this.rdbTempFile.deleteOnExit();
			} catch (IOException e) {
				setStatus(AnalyzeStatus.ERROR);
				this.rdbTempFile.deleteOnExit();
				e.printStackTrace();
			} finally {
				this.excuter = null;
				this.rdbTempFile.deleteOnExit();
				// 关闭写数据到ES后台进程
				AnalyzerWorker.stopWorker();
				System.gc();
				LOG.info("All Analyze done...");
			}
		}

		public JSONObject currentStatus() {
			JSONObject status = this.excuter.result();
			status.put("port", SimpleAnalyzerManager.currentPort());
			return status;
		}

	}
}
