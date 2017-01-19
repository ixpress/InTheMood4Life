/*
 * Copyright (C) 2017 Lambour Sebastien
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.breizhbeans.itm4l.service.impl

import com.google.common.base.Strings
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.groovy.core.buffer.Buffer
import org.breizhbeans.itm4l.beddit.DataFrame
import org.breizhbeans.itm4l.exception.FunctionalException
import org.breizhbeans.itm4l.exception.Functionals
import org.breizhbeans.itm4l.parameters.UserParameters
import org.breizhbeans.itm4l.service.AbstractService
import org.breizhbeans.itm4l.service.ServiceRequest
import com.julienviet.groovy.childprocess.Process

import java.util.regex.Matcher
import java.util.regex.Pattern

class BluetoothService extends AbstractService {

  private static enum BleState {
    SCAN, RECORDING, IDLE;
  }

  private static BleState bleState = BleState.IDLE
  private static Process process = null
  private String scriptsDir = null
  private Map<String,String> devices = [:]

  def logger = LoggerFactory.getLogger(BluetoothService.class)

  public BluetoothService(Map<String, Object> config) {
    services.put('scan/start', this.&startScan)
    services.put('scan/devices', this.&devices)
    services.put('scan/stop', this.&stopScan)

    services.put('pair', this.&pair)

    services.put('record/start', this.&startRecord)
    services.put('record/stop', this.&stopRecord)

    scriptsDir= config.get("scripts")
  }

  private void startScan(ServiceRequest context, def request) {
    if (!bleState.equals(BleState.IDLE)) {
      throw new FunctionalException(context.service, Functionals.BLE_NOT_IDLE, "ble is not IDLE (${bleState.name()})")
    }

    // kill current process is exists
    if (process!=null && process.running) {
      logger.debug("bt:lescan:kill:pid:${process.pid()}")
      process.kill(true)
    }

    devices.clear()

    def options = [
        env:Process.env()
    ]
    def resetBtProcess = Process.create(context.vertx, "${scriptsDir}/resetbt.sh", options)

    resetBtProcess.exitHandler({ code ->
      logger.debug("bt:lescan:reset:status:${code}")

      process = Process.create(context.vertx, "stdbuf", [ "-i0", "-o0", "-e0", "hcitool","lescan"], options)


      process.stdout().handler({ buff ->
        // Extract bluetooth address
        // ^([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}) (Beddit.*?$)
        Pattern btPattern = Pattern.compile("^([0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}:[0-9A-F]{2}) (Beddit.*?\$)");
        String btScan = buff.toString()
        Matcher m = btPattern.matcher(btScan)

        if (m.find()) {
          logger.debug("bt:lescan:match:${m.group(1)}:${m.group(2)}")
          devices.put(m.group(1), m.group(2))
        } else {
          logger.debug("bt:lescan:nomatch:${btScan}")
        }
      })

      process.stderr().handler({ buff ->
        logger.debug("bt:lescan:err:${buff.toString()}")
      })

      process.start({ processStarted ->
        process = processStarted
        logger.debug("bt:lescan:pid:${processStarted.pid()}")
      })
    })


    resetBtProcess.start()

    // update the ble scan
    bleState= BleState.SCAN
    context.replyHandler.call(new JsonObject())
  }

  private void stopScan(ServiceRequest context, def request) {
    if (!bleState.equals(BleState.SCAN)) {
      throw new FunctionalException(context.service, Functionals.BLE_SCAN_DISABLED, "Scan not running state=(${bleState.name()})")
    }

    stopScan()
    context.replyHandler.call(new JsonObject())
  }

  private void devices(ServiceRequest context, def request) {
    def output = new JsonObject()

    JsonArray jsonDevices = new JsonArray()

    for ( d in devices ) {
      JsonObject jsonDevice = new JsonObject()
      jsonDevice.put("address", d.key)
      jsonDevice.put("name", d.value)
      jsonDevices.add(jsonDevice)
    }

    output.put("devices", jsonDevices)
    context.replyHandler.call(output)
  }


  private void pair(ServiceRequest context, def request) {
    // Paring only available during the scan process
    if (!bleState.equals(BleState.SCAN)) {
      throw new FunctionalException(context.service, Functionals.BLE_SCAN_DISABLED, "Scan not running state=(${bleState.name()})")
    }
    // get the address
    String address = getStringAttribute(request, "address", true)

    String deviceName = devices.get(address)

    if (Strings.isNullOrEmpty(deviceName)) {
      throw new FunctionalException(context.service, Functionals.BLE_UNKNOWN_ADDRESS, "Unknown bluetooth address")
    }

    // save the address
    UserParameters.setDevice(address, deviceName)

    // stop the scan
    stopScan()
    def output = new JsonObject()
    context.replyHandler.call(output)
  }


  private void startRecord(ServiceRequest context, def request) {
    if (!bleState.equals(BleState.IDLE)) {
      throw new FunctionalException(context.service, Functionals.BLE_NOT_IDLE, "Ble is not IDLE state=(${bleState.name()})")
    }

    JsonObject device = UserParameters.getDevice()

    if (device == null) {
      throw new FunctionalException(context.service, Functionals.BLE_NO_DEVICE, "No Beddit device paired")
    }

    String address = device.getString("address")

    // kill current process is exists
    if (process!=null && process.running) {
      logger.debug("bt:record:kill:pid:${process.pid()}")
      process.kill(true)
    }

    def options = [
        env:Process.env()
    ]

    process = Process.create(context.vertx, "gatttool", ["-b", address,"-I"], options)

    process.stdout().handler({ buff ->
      String message = buff.toString()

      switch (bleState) {
        case BleState.IDLE:
          // dirty process scrapping
          if (message.contains("Connection successful")) {
            // writes 01 on the handle 0x0010
            process.stdin().write(Buffer.buffer("char-write-cmd 0x0010 0100\n"))
            process.stdin().write(Buffer.buffer("char-write-cmd 0x000e 01\n"))
            bleState= BleState.RECORDING
          }

          if (message.contains("connect error")) {
            process.kill(true)
            process = null
          }
          break;

        case BleState.RECORDING:
          // this is a handle notification
          if (message.contains("0x000e")) {
            //println("recording:${message}")
            // take first the timestamp before any processing
            long timestamp = System.currentTimeMillis()
            // keep only data after value
            String value = message.substring(message.lastIndexOf("value:") + 1)
            value = value.replace(" ", "")
            // post this data on the event bus
            context.vertx.eventBus().send("streamProcessing", new DataFrame(timestamp, value))
          }
          break
      }
    })

    process.start()

    // try to connect to the Beddit device
    process.stdin().write(Buffer.buffer("connect\n"))


    def output = new JsonObject()
    context.replyHandler.call(output)
  }

  private void stopRecord(ServiceRequest context, def request) {

    if (!bleState.equals(BleState.RECORDING)) {
      throw new FunctionalException(context.service, Functionals.BLE_NOT_RECORDING, "Ble is not recording state=(${bleState.name()})")
    }

    if (process!=null && process.running) {
      // sends stop command
      process.stdin().write(Buffer.buffer("char-write-cmd 0x0010 0000\n"))
      process.stdin().write(Buffer.buffer("disconnect\n"))
      process.stdin().write(Buffer.buffer("quit\n"))
      process.kill(true)
    }

    bleState= BleState.IDLE
    def output = new JsonObject()
    context.replyHandler.call(output)
  }

  private void stopScan() {
    // kill current process is exists
    if (process!=null && process.running) {
      process.kill(true)
      process = null
    }
    devices.clear()

    // update the ble scan
    bleState= BleState.IDLE
  }

}
