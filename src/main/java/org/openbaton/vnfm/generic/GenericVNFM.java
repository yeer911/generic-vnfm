package org.openbaton.vnfm.generic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import org.apache.commons.codec.binary.Base64;
import org.openbaton.catalogue.mano.common.Event;
import org.openbaton.catalogue.mano.common.Ip;
import org.openbaton.catalogue.mano.common.LifecycleEvent;
import org.openbaton.catalogue.mano.descriptor.VirtualDeploymentUnit;
import org.openbaton.catalogue.mano.record.VNFCInstance;
import org.openbaton.catalogue.mano.record.VNFRecordDependency;
import org.openbaton.catalogue.mano.record.VirtualNetworkFunctionRecord;
import org.openbaton.catalogue.nfvo.Action;
import org.openbaton.catalogue.nfvo.ConfigurationParameter;
import org.openbaton.catalogue.nfvo.DependencyParameters;
import org.openbaton.catalogue.nfvo.Script;
import org.openbaton.catalogue.nfvo.VNFCDependencyParameters;
import org.openbaton.catalogue.nfvo.VimInstance;
import org.openbaton.common.vnfm_sdk.AbstractVnfm;
import org.openbaton.common.vnfm_sdk.amqp.AbstractVnfmSpringAmqp;
import org.openbaton.common.vnfm_sdk.exception.VnfmSdkException;
import org.openbaton.common.vnfm_sdk.interfaces.EmsRegistrator;
import org.openbaton.common.vnfm_sdk.utils.VnfmUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;

/**
 * Created by mob on 16.07.15.
 */
@EnableScheduling
public class GenericVNFM extends AbstractVnfmSpringAmqp {

  @Autowired private EmsRegistrator emsRegistrator;
  private Gson parser = new GsonBuilder().setPrettyPrinting().create();
  private String scriptPath;

  @Value("${vnfm.ems.start.timeout:500}")
  private int waitForEms;

  @Value("${vnfm.ems.script.logpath:/var/log/openbaton/scriptsLog/}")
  private String scriptsLogPath;

  @Value("${vnfm.ems.script.old:60}")
  private int old;

  public static void main(String[] args) {
    SpringApplication.run(GenericVNFM.class, args);
  }

  private static String convertStreamToString(InputStream is) {
    Scanner s = new Scanner(is).useDelimiter("\\A");
    return s.hasNext() ? s.next() : "";
  }

  @Override
  public VirtualNetworkFunctionRecord instantiate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      Object scripts,
      Map<String, Collection<VimInstance>> vimInstances)
      throws Exception {

    log.info(
        "Instantiation of VirtualNetworkFunctionRecord " + virtualNetworkFunctionRecord.getName());
    if (null != scripts) {
      this.saveScriptOnEms(virtualNetworkFunctionRecord, scripts);
    }

    for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
      for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
        log.debug("VNFCInstance: " + vnfcInstance);
      }
    }

    String output = "\n--------------------\n--------------------\n";
    for (String result : executeScriptsForEvent(virtualNetworkFunctionRecord, Event.INSTANTIATE)) {
      output +=
          this.parser
              .fromJson(result, JsonObject.class)
              .get("output")
              .getAsString()
              .replaceAll("\\\\n", "\n");
      output += "\n--------------------\n";
    }
    output += "\n--------------------\n";
    log.info("Executed script for INSTANTIATE. Output was: \n\n" + output);
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void query() {}

  @Override
  public VirtualNetworkFunctionRecord scale(
      Action scaleInOrOut,
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance component,
      Object scripts,
      VNFRecordDependency dependency)
      throws Exception {
    if (scaleInOrOut.ordinal() == Action.SCALE_OUT.ordinal()) {
      log.info("Created VNFComponent");

      saveScriptOnEms(component, scripts);
      String output = "\n--------------------\n--------------------\n";
      for (String result :
          executeScriptsForEvent(virtualNetworkFunctionRecord, component, Event.INSTANTIATE)) {
        output +=
            this.parser
                .fromJson(result, JsonObject.class)
                .get("output")
                .getAsString()
                .replaceAll("\\\\n", "\n");
        output += "\n--------------------\n";
      }
      output += "\n--------------------\n";
      log.info("Executed script for INSTANTIATE. Output was: \n\n" + output);

      if (dependency != null) {
        output = "\n--------------------\n--------------------\n";
        for (String result :
            executeScriptsForEvent(
                virtualNetworkFunctionRecord, component, Event.CONFIGURE, dependency)) {
          output +=
              this.parser
                  .fromJson(result, JsonObject.class)
                  .get("output")
                  .getAsString()
                  .replaceAll("\\\\n", "\n");
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info("Executed script for CONFIGURE. Output was: \n\n" + output);
      }

      if ((component.getState() == null) || !component.getState().equals("standby")) {
        if (VnfmUtils.getLifecycleEvent(
                virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
            != null) {
          output = "\n--------------------\n--------------------\n";
          for (String result :
              executeScriptsForEvent(virtualNetworkFunctionRecord, component, Event.START)) {
            output +=
                this.parser
                    .fromJson(result, JsonObject.class)
                    .get("output")
                    .getAsString()
                    .replaceAll("\\\\n", "\n");
            output += "\n--------------------\n";
          }
          output += "\n--------------------\n";
          log.info("Executed script for START. Output was: \n\n" + output);
        }
      }

      log.trace("HB_VERSION == " + virtualNetworkFunctionRecord.getHb_version());
      return virtualNetworkFunctionRecord;
    } else { // SCALE_IN

      String output = "\n--------------------\n--------------------\n";
      for (String result :
          executeScriptsForEventOnVnfr(virtualNetworkFunctionRecord, component, Event.SCALE_IN)) {
        output +=
            this.parser
                .fromJson(result, JsonObject.class)
                .get("output")
                .getAsString()
                .replaceAll("\\\\n", "\n");
        output += "\n--------------------\n";
      }
      output += "\n--------------------\n";
      log.info("Executed script for SCALE_IN. Output was: \n\n" + output);

      return virtualNetworkFunctionRecord;
    }
  }

  private Iterable<? extends String> executeScriptsForEventOnVnfr(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstanceRemote,
      Event event)
      throws Exception {
    Map<String, String> env = getMap(virtualNetworkFunctionRecord);
    Collection<String> res = new ArrayList<>();
    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);
    if (le != null) {
      log.trace(
          "The number of scripts for "
              + virtualNetworkFunctionRecord.getName()
              + " are: "
              + le.getLifecycle_events());
      for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
        for (VNFCInstance vnfcInstanceLocal : virtualDeploymentUnit.getVnfc_instance()) {
          for (String script : le.getLifecycle_events()) {
            log.info(
                "Sending script: "
                    + script
                    + " to VirtualNetworkFunctionRecord: "
                    + virtualNetworkFunctionRecord.getName()
                    + " on VNFCInstance: "
                    + vnfcInstanceLocal.getId());
            Map<String, String> tempEnv = new HashMap<>();
            for (Ip ip : vnfcInstanceLocal.getIps()) {
              log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
              tempEnv.put(ip.getNetName(), ip.getIp());
            }
            log.debug("adding floatingIp: " + vnfcInstanceLocal.getFloatingIps());
            for (Ip fip : vnfcInstanceLocal.getFloatingIps()) {
              tempEnv.put(fip.getNetName() + "_floatingIp", fip.getIp());
            }

            tempEnv.put("hostname", vnfcInstanceLocal.getHostname());

            if (vnfcInstanceRemote != null) {
              //TODO what should i put here?
              for (Ip ip : vnfcInstanceRemote.getIps()) {
                log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
                tempEnv.put("removing_" + ip.getNetName(), ip.getIp());
              }
              log.debug("adding floatingIp: " + vnfcInstanceRemote.getFloatingIps());
              for (Ip fip : vnfcInstanceRemote.getFloatingIps()) {
                tempEnv.put("removing_" + fip.getNetName() + "_floatingIp", fip.getIp());
              }

              tempEnv.put("removing_" + "hostname", vnfcInstanceRemote.getHostname());
            }

            tempEnv = modifyUnsafeEnvVarNames(tempEnv);
            env.putAll(tempEnv);
            log.info("The Environment Variables for script " + script + " are: " + env);

            String command = getJsonObject("EXECUTE", script, env).toString();
            String output = executeActionOnEMS(vnfcInstanceLocal.getHostname(), command);
            res.add(output);

            saveLogToFile(virtualNetworkFunctionRecord, script, vnfcInstanceLocal, output);
            for (String key : tempEnv.keySet()) {
              env.remove(key);
            }
          }
        }
      }
    }
    return res;
  }

  private Map<String, String> modifyUnsafeEnvVarNames(Map<String, String> env) {

    Map<String, String> result = new HashMap<>();

    for (Entry<String, String> entry : env.entrySet()) {
      result.put(entry.getKey().replaceAll("[^A-Za-z0-9_]", "_"), entry.getValue());
    }

    return result;
  }

  private Iterable<String> executeScriptsForEvent(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstance,
      Event event)
      throws Exception {
    Map<String, String> env = getMap(virtualNetworkFunctionRecord);
    List<String> res = new ArrayList<>();
    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);

    if (le != null) {
      log.trace(
          "The number of scripts for "
              + virtualNetworkFunctionRecord.getName()
              + " are: "
              + le.getLifecycle_events());
      for (String script : le.getLifecycle_events()) {
        log.info(
            "Sending script: "
                + script
                + " to VirtualNetworkFunctionRecord: "
                + virtualNetworkFunctionRecord.getName());
        Map<String, String> tempEnv = new HashMap<>();
        for (Ip ip : vnfcInstance.getIps()) {
          log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
          tempEnv.put(ip.getNetName(), ip.getIp());
        }
        log.debug("adding floatingIp: " + vnfcInstance.getFloatingIps());
        for (Ip fip : vnfcInstance.getFloatingIps()) {
          tempEnv.put(fip.getNetName() + "_floatingIp", fip.getIp());
        }

        tempEnv.put("hostname", vnfcInstance.getHostname());

        tempEnv = modifyUnsafeEnvVarNames(tempEnv);
        env.putAll(tempEnv);
        log.info("The Environment Variables for script " + script + " are: " + env);

        String command = getJsonObject("EXECUTE", script, env).toString();
        if (event.ordinal() == Event.SCALE_IN.ordinal()) {
          for (VirtualDeploymentUnit vdu : virtualNetworkFunctionRecord.getVdu()) {
            for (VNFCInstance vnfcInstance1 : vdu.getVnfc_instance()) {

              String output = executeActionOnEMS(vnfcInstance1.getHostname(), command);
              res.add(output);
              saveLogToFile(virtualNetworkFunctionRecord, script, vnfcInstance1, output);
            }
          }
        } else {
          res.add(executeActionOnEMS(vnfcInstance.getHostname(), command));
        }

        for (String key : tempEnv.keySet()) {
          env.remove(key);
        }
      }
    }
    return res;
  }

  private void saveLogToFile(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      String script,
      VNFCInstance vnfcInstance1,
      String output)
      throws IOException {
    if (this.old > 0) {
      String path =
          this.scriptsLogPath
              + virtualNetworkFunctionRecord.getName()
              + "/"
              + vnfcInstance1.getHostname()
              + ".log";
      File f = new File(path);

      if (!f.exists()) {
        f.getParentFile().mkdirs();
        f.createNewFile();
      }

      Files.write(
          Paths.get(path), ("Script : " + script + "\n\n").getBytes(), StandardOpenOption.APPEND);
      Files.write(
          Paths.get(path),
          this.parser
              .fromJson(output, JsonObject.class)
              .get("output")
              .getAsString()
              .replaceAll("\\\\n", "\n")
              .getBytes(),
          StandardOpenOption.APPEND);
      Files.write(
          Paths.get(path),
          "\n\n\n~~~~~~~~~~~~~~~~~~~~~~~~~\n#########################\n~~~~~~~~~~~~~~~~~~~~~~~~~\n\n\n"
              .getBytes(),
          StandardOpenOption.APPEND);
    }
  }

  private Iterable<String> executeScriptsForEvent(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstance,
      Event event,
      String cause)
      throws Exception {
    Map<String, String> env = getMap(virtualNetworkFunctionRecord);
    List<String> res = new LinkedList<>();
    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);

    if (le != null) {
      log.trace(
          "The number of scripts for "
              + virtualNetworkFunctionRecord.getName()
              + " are: "
              + le.getLifecycle_events());
      for (String script : le.getLifecycle_events()) {
        log.info(
            "Sending script: "
                + script
                + " to VirtualNetworkFunctionRecord: "
                + virtualNetworkFunctionRecord.getName());
        Map<String, String> tempEnv = new HashMap<>();
        for (Ip ip : vnfcInstance.getIps()) {
          log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
          tempEnv.put(ip.getNetName(), ip.getIp());
        }
        log.debug("adding floatingIp: " + vnfcInstance.getFloatingIps());
        for (Ip fip : vnfcInstance.getFloatingIps()) {
          tempEnv.put(fip.getNetName() + "_floatingIp", fip.getIp());
        }

        tempEnv.put("hostname", vnfcInstance.getHostname());
        //Add cause to the environment variables
        tempEnv.put("cause", cause);

        tempEnv = modifyUnsafeEnvVarNames(tempEnv);
        env.putAll(tempEnv);
        log.info("The Environment Variables for script " + script + " are: " + env);

        String command = getJsonObject("EXECUTE", script, env).toString();
        String output = executeActionOnEMS(vnfcInstance.getHostname(), command);
        res.add(output);
        saveLogToFile(virtualNetworkFunctionRecord, script, vnfcInstance, output);
        for (String key : tempEnv.keySet()) {
          env.remove(key);
        }
      }
    }
    return res;
  }

  private Iterable<String> executeScriptsForEvent(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance vnfcInstance,
      Event event,
      VNFRecordDependency dependency)
      throws Exception {
    Map<String, String> env = getMap(virtualNetworkFunctionRecord);
    List<String> res = new ArrayList<>();
    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);
    log.trace(
        "The number of scripts for "
            + virtualNetworkFunctionRecord.getName()
            + " are: "
            + le.getLifecycle_events());
    log.debug("DEPENDENCY IS: " + dependency);
    if (le != null) {
      for (String script : le.getLifecycle_events()) {
        String type = script.substring(0, script.indexOf('_'));
        VNFCDependencyParameters vnfcDependencyParameters =
            dependency.getVnfcParameters().get(type);
        if (vnfcDependencyParameters != null) {
          log.debug(
              "There are "
                  + vnfcDependencyParameters.getParameters().size()
                  + " VNFCInstanceForeign");
          for (String vnfcForeignId : vnfcDependencyParameters.getParameters().keySet()) {
            log.info("Running script: " + script + " for VNFCInstance foreign id " + vnfcForeignId);

            log.info(
                "Sending command: "
                    + script
                    + " to adding relation with type: "
                    + type
                    + " from VirtualNetworkFunctionRecord "
                    + virtualNetworkFunctionRecord.getName());

            Map<String, String> tempEnv = new HashMap<>();

            //Adding own ips
            for (Ip ip : vnfcInstance.getIps()) {
              log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
              tempEnv.put(ip.getNetName(), ip.getIp());
            }

            //Adding own floating ip
            log.debug("adding floatingIp: " + vnfcInstance.getFloatingIps());
            for (Ip fip : vnfcInstance.getFloatingIps()) {
              tempEnv.put(fip.getNetName() + "_floatingIp", fip.getIp());
            }
            //Adding foreign parameters such as ip
            if (script.contains("_")) {
              //Adding foreign parameters such as ip
              Map<String, String> parameters = dependency.getParameters().get(type).getParameters();
              for (Entry<String, String> param : parameters.entrySet()) {
                tempEnv.put(type + "_" + param.getKey(), param.getValue());
              }

              Map<String, String> parametersVNFC =
                  vnfcDependencyParameters.getParameters().get(vnfcForeignId).getParameters();
              for (Entry<String, String> param : parametersVNFC.entrySet()) {
                tempEnv.put(type + "_" + param.getKey(), param.getValue());
              }
            }

            tempEnv.put("hostname", vnfcInstance.getHostname());
            tempEnv = modifyUnsafeEnvVarNames(tempEnv);
            env.putAll(tempEnv);
            log.info("The Environment Variables for script " + script + " are: " + env);

            String command = getJsonObject("EXECUTE", script, env).toString();
            String output = executeActionOnEMS(vnfcInstance.getHostname(), command);
            res.add(output);

            saveLogToFile(virtualNetworkFunctionRecord, script, vnfcInstance, output);
            for (String key : tempEnv.keySet()) {
              env.remove(key);
            }
          }
        }
      }
    }
    return res;
  }

  @Override
  public void checkInstantiationFeasibility() {}

  @Override
  public VirtualNetworkFunctionRecord heal(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      VNFCInstance component,
      String cause)
      throws Exception {

    if ("switchToStandby".equals(cause)) {
      for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
        for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
          if (vnfcInstance.getId().equals(component.getId())
              && "standby".equals(vnfcInstance.getState())) {
            log.debug("Activation of the standby component");
            if (VnfmUtils.getLifecycleEvent(
                    virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
                != null) {
              log.debug(
                  "Executed scripts for event START "
                      + this.executeScriptsForEvent(
                          virtualNetworkFunctionRecord, component, Event.START));
            }
            log.debug("Changing the status from standby to active");
            //This is inside the vnfr
            vnfcInstance.setState("ACTIVE");
            // This is a copy of the object received as parameter and modified.
            // It will be sent to the orchestrator
            component.setState("ACTIVE");
            break;
          }
        }
      }
    } else if (VnfmUtils.getLifecycleEvent(
            virtualNetworkFunctionRecord.getLifecycle_event(), Event.HEAL)
        != null) {
      if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.HEAL)
              .getLifecycle_events()
          != null) {
        log.debug("Heal method started");
        log.info("-----------------------------------------------------------------------");
        String output = "\n--------------------\n--------------------\n";
        for (String result :
            executeScriptsForEvent(virtualNetworkFunctionRecord, component, Event.HEAL, cause)) {
          output +=
              this.parser
                  .fromJson(result, JsonObject.class)
                  .get("output")
                  .getAsString()
                  .replaceAll("\\\\n", "\n");
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info("Executed script for HEAL. Output was: \n\n" + output);
        log.info("-----------------------------------------------------------------------");
      }
    }
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void updateSoftware() {}

  @Override
  public void upgradeSoftware() {}

  @Override
  public VirtualNetworkFunctionRecord modify(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFRecordDependency dependency)
      throws Exception {
    log.trace(
        "VirtualNetworkFunctionRecord VERSION is: " + virtualNetworkFunctionRecord.getHb_version());
    log.info("executing modify for VNFR: " + virtualNetworkFunctionRecord.getName());

    log.debug("Got dependency: " + dependency);
    log.debug("Parameters are: ");
    for (Entry<String, DependencyParameters> entry : dependency.getParameters().entrySet()) {
      log.debug("Source type: " + entry.getKey());
      log.debug("Parameters: " + entry.getValue().getParameters());
    }

    if (VnfmUtils.getLifecycleEvent(
            virtualNetworkFunctionRecord.getLifecycle_event(), Event.CONFIGURE)
        != null) {
      log.debug(
          "LifeCycle events: "
              + VnfmUtils.getLifecycleEvent(
                      virtualNetworkFunctionRecord.getLifecycle_event(), Event.CONFIGURE)
                  .getLifecycle_events());
      log.info("-----------------------------------------------------------------------");
      String output = "\n--------------------\n--------------------\n";
      for (String result :
          executeScriptsForEvent(virtualNetworkFunctionRecord, Event.CONFIGURE, dependency)) {
        output +=
            this.parser
                .fromJson(result, JsonObject.class)
                .get("output")
                .getAsString()
                .replaceAll("\\\\n", "\n");
        output += "\n--------------------\n";
      }
      output += "\n--------------------\n";
      log.info("Executed script for CONFIGURE. Output was: \n\n" + output);
      log.info("-----------------------------------------------------------------------");
    } else {
      log.debug("No LifeCycle events for Event.CONFIGURE");
    }
    return virtualNetworkFunctionRecord;
  }

  //When the EMS reveive a script which terminate the vnf, the EMS is still running.
  //Once the vnf is terminated NFVO requests deletion of resources (MANO B.5) and the EMS will be terminated.
  @Override
  public VirtualNetworkFunctionRecord terminate(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    log.debug("Termination of VNF: " + virtualNetworkFunctionRecord.getName());
    if (VnfmUtils.getLifecycleEvent(
            virtualNetworkFunctionRecord.getLifecycle_event(), Event.TERMINATE)
        != null) {
      String output = "\n--------------------\n--------------------\n";
      for (String result : executeScriptsForEvent(virtualNetworkFunctionRecord, Event.TERMINATE)) {
        output +=
            this.parser
                .fromJson(result, JsonObject.class)
                .get("output")
                .getAsString()
                .replaceAll("\\\\n", "\n");
        output += "\n--------------------\n";
      }
      output += "\n--------------------\n";
      log.info("Executed script for TERMINATE. Output was: \n\n" + output);
    }
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void handleError(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {
    log.error("Received Error for VNFR " + virtualNetworkFunctionRecord.getName());
    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.ERROR)
        != null) {
      String output = "\n--------------------\n--------------------\n";
      try {
        for (String result : executeScriptsForEvent(virtualNetworkFunctionRecord, Event.ERROR)) {
          output +=
              this.parser
                  .fromJson(result, JsonObject.class)
                  .get("output")
                  .getAsString()
                  .replaceAll("\\\\n", "\n");
          output += "\n--------------------\n";
        }
      } catch (Exception e) {
        e.printStackTrace();
        log.error("Exception executing Error handling");
      }
      output += "\n--------------------\n";
      log.info("Executed script for ERROR. Output was: \n\n" + output);
    }
  }

  @Override
  protected void fillSpecificProvides(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {
    for (ConfigurationParameter configurationParameter :
        virtualNetworkFunctionRecord.getProvides().getConfigurationParameters()) {
      if (!configurationParameter.getConfKey().startsWith("#nfvo:")) {
        configurationParameter.setValue(String.valueOf((int) (Math.random() * 100)));
        log.debug(
            "Setting: "
                + configurationParameter.getConfKey()
                + " with value: "
                + configurationParameter.getValue());
      }
    }
  }

  @Override
  public VirtualNetworkFunctionRecord start(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {

    log.info("Starting vnfr: " + virtualNetworkFunctionRecord.getName());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
        != null) {
      if (VnfmUtils.getLifecycleEvent(
                  virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result : executeScriptsForEvent(virtualNetworkFunctionRecord, Event.START)) {
          output +=
              this.parser
                  .fromJson(result, JsonObject.class)
                  .get("output")
                  .getAsString()
                  .replaceAll("\\\\n", "\n");
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info("Executed script for START. Output was: \n\n" + output);
      }
    }
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord stop(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {

    log.info("Stopping vnfr: " + virtualNetworkFunctionRecord.getName());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
        != null) {
      if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result : executeScriptsForEvent(virtualNetworkFunctionRecord, Event.STOP)) {
          output +=
              this.parser
                  .fromJson(result, JsonObject.class)
                  .get("output")
                  .getAsString()
                  .replaceAll("\\\\n", "\n");
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info("Executed script for STOP. Output was: \n\n" + output);
      }
    }
    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord startVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {

    log.info("Starting vnfc instance: " + vnfcInstance.getHostname());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
        != null) {
      if (VnfmUtils.getLifecycleEvent(
                  virtualNetworkFunctionRecord.getLifecycle_event(), Event.START)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result :
            executeScriptsForEvent(virtualNetworkFunctionRecord, vnfcInstance, Event.START)) {
          output +=
              this.parser
                  .fromJson(result, JsonObject.class)
                  .get("output")
                  .getAsString()
                  .replaceAll("\\\\n", "\n");
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info(
            "Executed script for START on VNFC Instance "
                + vnfcInstance.getHostname()
                + ". Output was: \n\n"
                + output);
      }
    }

    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord stopVNFCInstance(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, VNFCInstance vnfcInstance)
      throws Exception {

    log.info("Stopping vnfc instance: " + vnfcInstance.getHostname());

    if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
        != null) {
      if (VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), Event.STOP)
              .getLifecycle_events()
          != null) {
        String output = "\n--------------------\n--------------------\n";
        for (String result :
            executeScriptsForEvent(virtualNetworkFunctionRecord, vnfcInstance, Event.STOP)) {
          output +=
              this.parser
                  .fromJson(result, JsonObject.class)
                  .get("output")
                  .getAsString()
                  .replaceAll("\\\\n", "\n");
          output += "\n--------------------\n";
        }
        output += "\n--------------------\n";
        log.info(
            "Executed script for STOP on VNFC Instance "
                + vnfcInstance.getHostname()
                + ". Output was: \n\n"
                + output);
      }
    }

    return virtualNetworkFunctionRecord;
  }

  @Override
  public VirtualNetworkFunctionRecord configure(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) throws Exception {
    return virtualNetworkFunctionRecord;
  }

  @Override
  public void NotifyChange() {}

  @Override
  protected void checkEMS(String hostname) {
    int i = 0;
    while (true) {
      log.debug("Waiting for " + hostname + " ems to be started... (" + i * 5 + " secs)");
      i++;
      try {
        checkEmsStarted(hostname);
        break;
      } catch (RuntimeException e) {
        if (i == this.waitForEms / 5) {
          throw e;
        }
        try {
          Thread.sleep(5000);
        } catch (InterruptedException e1) {
          e1.printStackTrace();
        }
      }
    }
  }

  @Override
  protected void checkEmsStarted(String hostname) {
    if (!this.emsRegistrator.getHostnames().contains(hostname.toLowerCase())) {
      throw new RuntimeException("no ems for hostame: " + hostname);
    }
    this.emsRegistrator.unregister(hostname.toLowerCase());
  }

  private String executeActionOnEMS(String vduHostname, String command) throws Exception {
    log.trace("Sending message and waiting: " + command + " to " + vduHostname);
    log.info("Waiting answer from EMS - " + vduHostname);

    String response = this.vnfmHelper.sendAndReceive(command, "vnfm." + vduHostname + ".actions");

    log.debug("Received from EMS (" + vduHostname + "): " + response);

    if (response == null) {
      throw new NullPointerException("Response from EMS is null");
    }

    JsonObject jsonObject = this.parser.fromJson(response, JsonObject.class);

    if (jsonObject.get("status").getAsInt() == 0) {
      try {
        log.debug("Output from EMS (" + vduHostname + ") is: " + jsonObject.get("output"));
      } catch (Exception e) {
        e.printStackTrace();
        throw e;
      }
    } else {
      log.error(jsonObject.get("err").getAsString());
      throw new VnfmSdkException(
          "EMS ("
              + vduHostname
              + ") had the following error: "
              + jsonObject.get("err").getAsString());
    }
    return response;
  }

  public Iterable<String> executeScriptsForEvent(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, Event event)
      throws Exception { //TODO make it parallel
    Map<String, String> env = getMap(virtualNetworkFunctionRecord);
    Collection<String> res = new ArrayList<>();
    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);

    if (le != null) {
      log.trace(
          "The number of scripts for "
              + virtualNetworkFunctionRecord.getName()
              + " are: "
              + le.getLifecycle_events());
      for (String script : le.getLifecycle_events()) {
        log.info(
            "Sending script: "
                + script
                + " to VirtualNetworkFunctionRecord: "
                + virtualNetworkFunctionRecord.getName());
        for (VirtualDeploymentUnit vdu : virtualNetworkFunctionRecord.getVdu()) {
          for (VNFCInstance vnfcInstance : vdu.getVnfc_instance()) {

            Map<String, String> tempEnv = new HashMap<>();
            for (Ip ip : vnfcInstance.getIps()) {
              log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
              tempEnv.put(ip.getNetName(), ip.getIp());
            }
            log.debug("adding floatingIp: " + vnfcInstance.getFloatingIps());
            for (Ip fip : vnfcInstance.getFloatingIps()) {
              tempEnv.put(fip.getNetName() + "_floatingIp", fip.getIp());
            }

            tempEnv.put("hostname", vnfcInstance.getHostname());
            tempEnv = modifyUnsafeEnvVarNames(tempEnv);
            env.putAll(tempEnv);
            log.info("Environment Variables are: " + env);

            String command = getJsonObject("EXECUTE", script, env).toString();
            String output = executeActionOnEMS(vnfcInstance.getHostname(), command);
            res.add(output);

            saveLogToFile(virtualNetworkFunctionRecord, script, vnfcInstance, output);
            for (String key : tempEnv.keySet()) {
              env.remove(key);
            }
          }
        }
      }
    }
    return res;
  }

  public Iterable<String> executeScriptsForEvent(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord,
      Event event,
      VNFRecordDependency dependency)
      throws Exception {
    Map<String, String> env = getMap(virtualNetworkFunctionRecord);
    LifecycleEvent le =
        VnfmUtils.getLifecycleEvent(virtualNetworkFunctionRecord.getLifecycle_event(), event);
    List<String> res = new ArrayList<>();
    if (le != null) {
      for (String script : le.getLifecycle_events()) {

        String type = null;
        if (script.contains("_")) {
          type = script.substring(0, script.indexOf('_'));
          log.info(
              "Sending command: "
                  + script
                  + " to adding relation with type: "
                  + type
                  + " from VirtualNetworkFunctionRecord "
                  + virtualNetworkFunctionRecord.getName());
        }

        for (VirtualDeploymentUnit vdu : virtualNetworkFunctionRecord.getVdu()) {
          for (VNFCInstance vnfcInstance : vdu.getVnfc_instance()) {
            if (dependency.getVnfcParameters().get(type) != null) {
              for (String vnfcId :
                  dependency.getVnfcParameters().get(type).getParameters().keySet()) {

                Map<String, String> tempEnv = new HashMap<>();

                //Adding own ips
                for (Ip ip : vnfcInstance.getIps()) {
                  log.debug("Adding net: " + ip.getNetName() + " with value: " + ip.getIp());
                  tempEnv.put(ip.getNetName(), ip.getIp());
                }

                //Adding own floating ip
                for (Ip fip : vnfcInstance.getFloatingIps()) {
                  log.debug("adding floatingIp: " + fip.getNetName() + " = " + fip.getIp());
                  tempEnv.put(fip.getNetName() + "_floatingIp", fip.getIp());
                }

                if (script.contains("_")) {
                  //Adding foreign parameters such as ip
                  log.debug("Fetching parameter from dependency of type: " + type);
                  Map<String, String> parameters =
                      dependency.getParameters().get(type).getParameters();

                  for (Map.Entry<String, String> param : parameters.entrySet()) {
                    log.debug(
                        "adding param: " + type + "_" + param.getKey() + " = " + param.getValue());
                    tempEnv.put(type + "_" + param.getKey(), param.getValue());
                  }

                  Map<String, String> parametersVNFC =
                      dependency
                          .getVnfcParameters()
                          .get(type)
                          .getParameters()
                          .get(vnfcId)
                          .getParameters();
                  for (Map.Entry<String, String> param : parametersVNFC.entrySet()) {
                    log.debug(
                        "adding param: " + type + "_" + param.getKey() + " = " + param.getValue());
                    tempEnv.put(type + "_" + param.getKey(), param.getValue());
                  }
                }

                tempEnv.put("hostname", vnfcInstance.getHostname());
                tempEnv = modifyUnsafeEnvVarNames(tempEnv);
                env.putAll(tempEnv);
                log.info("Environment Variables are: " + env);

                String command = getJsonObject("EXECUTE", script, env).toString();
                String output = executeActionOnEMS(vnfcInstance.getHostname(), command);
                res.add(output);

                saveLogToFile(virtualNetworkFunctionRecord, script, vnfcInstance, output);
                for (String key : tempEnv.keySet()) {
                  env.remove(key);
                }
              }
            }
          }
        }
      }
    }
    return res;
  }

  public void saveScriptOnEms(
      VirtualNetworkFunctionRecord virtualNetworkFunctionRecord, Object scripts) throws Exception {

    log.debug("Scripts are: " + scripts.getClass().getName());

    if (scripts instanceof String) {
      String scriptLink = (String) scripts;
      log.debug("Scripts are: " + scriptLink);
      JsonObject jsonMessage = getJsonObject("CLONE_SCRIPTS", scriptLink, this.scriptPath);

      for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
        for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
          executeActionOnEMS(vnfcInstance.getHostname(), jsonMessage.toString());
        }
      }
    } else if (scripts instanceof Set) {
      Iterable<Script> scriptSet = (Set<Script>) scripts;

      for (Script script : scriptSet) {
        log.debug("Sending script encoded base64 ");
        String base64String = Base64.encodeBase64String(script.getPayload());
        log.trace("The base64 string is: " + base64String);
        JsonObject jsonMessage =
            getJsonObjectForScript("SAVE_SCRIPTS", base64String, script.getName(), this.scriptPath);
        for (VirtualDeploymentUnit virtualDeploymentUnit : virtualNetworkFunctionRecord.getVdu()) {
          for (VNFCInstance vnfcInstance : virtualDeploymentUnit.getVnfc_instance()) {
            executeActionOnEMS(vnfcInstance.getHostname(), jsonMessage.toString());
          }
        }
      }
    }
  }

  public void saveScriptOnEms(VNFCInstance component, Object scripts) throws Exception {

    log.debug("Scripts are: " + scripts.getClass().getName());

    if (scripts instanceof String) {
      String scriptLink = (String) scripts;
      log.debug("Scripts are: " + scriptLink);
      JsonObject jsonMessage = getJsonObject("CLONE_SCRIPTS", scriptLink, this.scriptPath);
      executeActionOnEMS(component.getHostname(), jsonMessage.toString());
    } else if (scripts instanceof Set) {
      Iterable<Script> scriptSet = (Set<Script>) scripts;
      for (Script script : scriptSet) {
        log.debug("Sending script encoded base64 ");
        String base64String = Base64.encodeBase64String(script.getPayload());
        log.trace("The base64 string is: " + base64String);
        JsonObject jsonMessage =
            getJsonObjectForScript("SAVE_SCRIPTS", base64String, script.getName(), this.scriptPath);
        executeActionOnEMS(component.getHostname(), jsonMessage.toString());
      }
    }
  }

  private JsonObject getJsonObject(String action, String payload, String scriptPath) {
    JsonObject jsonMessage = new JsonObject();
    jsonMessage.addProperty("action", action);
    jsonMessage.addProperty("payload", payload);
    jsonMessage.addProperty("script-path", scriptPath);
    return jsonMessage;
  }

  private JsonObject getJsonObject(String action, String payload, Map<String, String> env) {
    JsonObject jsonMessage = new JsonObject();
    jsonMessage.addProperty("action", action);
    jsonMessage.addProperty("payload", payload);
    jsonMessage.add("env", this.parser.fromJson(this.parser.toJson(env), JsonObject.class));
    return jsonMessage;
  }

  private JsonObject getJsonObjectForScript(
      String save_scripts, String payload, String name, String scriptPath) {
    JsonObject jsonMessage = new JsonObject();
    jsonMessage.addProperty("action", save_scripts);
    jsonMessage.addProperty("payload", payload);
    jsonMessage.addProperty("name", name);
    jsonMessage.addProperty("script-path", scriptPath);
    return jsonMessage;
  }

  private Map<String, String> getMap(VirtualNetworkFunctionRecord virtualNetworkFunctionRecord) {
    Map<String, String> res = new HashMap<>();
    for (ConfigurationParameter configurationParameter :
        virtualNetworkFunctionRecord.getProvides().getConfigurationParameters()) {
      res.put(configurationParameter.getConfKey(), configurationParameter.getValue());
    }
    for (ConfigurationParameter configurationParameter :
        virtualNetworkFunctionRecord.getConfigurations().getConfigurationParameters()) {
      res.put(configurationParameter.getConfKey(), configurationParameter.getValue());
    }
    res = modifyUnsafeEnvVarNames(res);
    return res;
  }

  @Override
  protected void setup() {
    super.setup();
    scriptPath = properties.getProperty("script-path", "/opt/openbaton/scripts");
    if (this.old > 0) {
      File f = new File(this.scriptsLogPath);
      f.mkdirs();
    }
  }

  @Override
  protected String getUserData() {
    String result = convertStreamToString(AbstractVnfm.class.getResourceAsStream("/user-data.sh"));

    log.debug(emsVersion);

    result = result.replace("export MONITORING_IP=", "export MONITORING_IP=" + monitoringIp);
    result = result.replace("export TIMEZONE=", "export TIMEZONE=" + timezone);
    result = result.replace("export BROKER_IP=", "export BROKER_IP=" + brokerIp);
    result = result.replace("export USERNAME=", "export USERNAME=" + username);
    result = result.replace("export PASSWORD=", "export PASSWORD=" + password);
    result = result.replace("export EXCHANGE_NAME=", "export EXCHANGE_NAME=" + exchangeName);
    result = result.replace("export EMS_HEARTBEAT=", "export EMS_HEARTBEAT=" + emsHeartbeat);
    result = result.replace("export EMS_AUTODELETE=", "export EMS_AUTODELETE=" + emsAutodelete);
    result = result.replace("export EMS_VERSION=", "export EMS_VERSION=" + emsVersion);
    result = result.replace("export ENDPOINT=", "export ENDPOINT=" + type);

    return result;
  }
}
