package org.myrobotlab.service.abstracts;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.myrobotlab.framework.Service;
import org.myrobotlab.framework.interfaces.ServiceInterface;
import org.myrobotlab.logging.LoggerFactory;
import org.myrobotlab.math.Mapper;
import org.myrobotlab.service.Runtime;
import org.myrobotlab.service.interfaces.EncoderControl;
import org.myrobotlab.service.interfaces.ServoControl;
import org.myrobotlab.service.interfaces.ServoController;
import org.myrobotlab.service.interfaces.ServoData;
import org.myrobotlab.service.interfaces.ServoData.ServoStatus;
import org.myrobotlab.service.interfaces.ServoDataListener;
import org.slf4j.Logger;

/**
 * 
 * @author GroG
 * 
 *         There was (in the past) great confusion about position of the servo.
 *         To make things clear - you must think of 2 different sets of data.
 * 
 *         The set of "what I want" versus the set of "what is".
 * 
 *         The set of "What I want" is all control related data. Target position
 *         would be part of this set. All data related to telling the servo what
 *         you want it to do.
 * 
 *         "What is" is all the data for which the servo reports. For example
 *         its "current position". You tell it what position you want it to be,
 *         and in turn it tells you what position its currently in. "Control"
 *         versus "Status". Do not mix these concepts, keep seperate variables.
 * 
 *         The mapper accepts inputs, the controller needs mapper outputs.
 *         Nothing outside of the servo controller should need the mapper
 *         outputs.
 * 
 *
 */
public abstract class AbstractServo extends Service implements ServoControl {

  @Deprecated // create a TimeEncoder to support this functionality
  public class Sweeper extends Thread {

    public Sweeper(String name) {
      super(String.format("%s.sweeper", name));
    }

    /**
     * Sweeping works on input, a thread is used as the "controller" (this is
     * input) and input sweeps back and forth - the servo parameters know what
     * to do for output
     */

    @Override
    public void run() {

      double sweepMin = 0.0;
      double sweepMax = 0.0;
      // start in the middle
      double sweepPos = mapper.getMinX() + (mapper.getMaxX() - mapper.getMinX()) / 2;
      isSweeping = true;

      try {
        while (isSweeping) {

          // set our range to be inside 'real' min & max input
          sweepMin = mapper.getMinX() + 1;
          sweepMax = mapper.getMaxX() - 1;

          // if pos is too small or too big flip direction
          if (sweepPos >= sweepMax || sweepPos <= sweepMin) {
            sweepStep = sweepStep * -1;
          }

          sweepPos += sweepStep;
          moveTo(sweepPos);
          Thread.sleep(sweepDelay);
        }
      } catch (Exception e) {
        isSweeping = false;
      }
    }

  }

  public final static Logger log = LoggerFactory.getLogger(AbstractServo.class);

  private static final long serialVersionUID = 1L;

  /**
   * acceleration of servo - not really implemented
   */
  protected Double acceleration;

  /**
   * 
   */
  protected Boolean autoDisable = false;

  /**
   * list of controller names this control is attached to
   */
  protected Set<String> controllers = new LinkedHashSet<>();

  /**
   * the current input position (pre-mapper)
   */
  protected Double currentPosInput;

  /**
   * if enabled then a pwm pulse is keeping the servo at the current position,
   * and movements are possible
   */
  protected boolean enabled = false;

  /**
   * The servos encoder - by "default" this will be a TimerEncoder - where a
   * timer calculates the expected time the servo will make and complete
   * movements, and some configured division of time is configured to send
   * position update events.
   * 
   * Unlike previous servo events which dependend on feedback coming from
   * Arduino, TimerEncoder events do not need an Arduino, in fact they don't
   * even need anything as computers are pretty good at counting time.
   * 
   * Arduino MrlEncoders (legacy) could potentially be substituted for a
   * TimerEncoder, however there is
   * 
   */
  EncoderControl encoder;

  protected boolean isSweeping = false;

  /**
   * last time the servo has moved
   */
  protected long lastActivityTime = 0;

  /**
   * servo's last position
   */
  protected Double lastPos;

  /**
   * list of servo listeners names
   */
  protected transient Set<String> listeners = new LinkedHashSet<>();

  /**
   * input mapper
   */
  protected Mapper mapper = new Mapper(0, 180, 0, 180);

  /**
   * maximum speed
   */
  protected Double maxSpeed;

  /**
   * status if the servo thinks its moving ..
   */
  protected boolean moving;

  /**
   * the 'pin' for this Servo - it is Integer because it can be in a state of
   * 'not set' or null.
   * 
   * pin is the ONLY value which cannot and will not be 'defaulted'
   */
  protected String pin;

  /**
   * the "current" position of the servo - this never gets updated from "command" methods such as
   * moveTo - its always status information, and its typically updated from an encoder of some form
   */
  protected Double currentPos;

  /**
   * default rest is 90 default target position will be 90 if not specified
   */
  protected Double rest = 90.0;

  /**
   * speed of the servo - null is no speed control
   */
  protected Double speed = null;

  protected int sweepDelay = 100;

  protected transient Thread sweeper = null;

  protected boolean sweepOneWay = false;

  protected Double sweepStep = 1.0;

  /**
   * synchronized servos - when this one moves, it sends move commands to these
   * servos
   */
  protected transient Set<String> syncedServos = new LinkedHashSet<>();

  /**
   * the calculated output for the servo
   */
  protected Double targetOutput;

  /**
   * the requested INPUT position of the servo
   */
  protected Double targetPos;

  /**
   * default blocking timeout if not specified
   */
  Integer timeoutMs = 30000;

  public AbstractServo(String reservedKey) {
    super(reservedKey);

    // this servo is interested in new services which support either ServoControllers or EncoderControl interfaces
    // we subscribe to runtime here for new services
    subscribe(Runtime.getInstance().getName(), "registered", this.getName(), "onRegistered");
  }

  //@Override
  public void attach(ServoController controller, Integer pin) throws Exception {
    attach(controller, pin, null);
  }

  //@Override
  public void attach(ServoController controller, Integer pin, Double pos) throws Exception {
    attach(controller, pin, null, null);
  }

 // @Override
  public void attach(ServoController controller, Integer pin, Double pos, Double speed) throws Exception {
    // default to no acceleration
    attach(controller, pin, pos, speed, 0.0);
  }
  /**
   * maximum complexity attach with reference to controller
   */
  //@Override
  public void attach(ServoController controller, Integer pin, Double pos, Double speed, Double acceleration) throws Exception {

    if (controller == null) {
      log.error("{}.attach(null)", getName());
      return;
    }

    // check if already attached
    if (controllers.contains(controller.getName())) {
      log.info("{}.attach({}) controller already attached", getName(), controller.getName());
      return;
    }

    // update pin if non-null value supplied
    if (pin != null) {
      setPin(pin);
    }

    // update pos if non-null value supplied
    if (pos != null) {
      targetPos = pos;
      lastPos = targetPos;
    }

    // update speed if non-null value supplied
    if (speed != null) {
      setVelocity(speed);
    }
    
    if (acceleration != null) {
      setAcceleration(acceleration);
    }

    controllers.add(controller.getName());
    controller.attach(this);
  }

  @Override
  public void attach(ServoDataListener service) {
    listeners.add(service.getName());
  }

 // @Override
  public void attach(String controllerName, Integer pin) throws Exception {
    attach(controllerName, pin, null);
  }

  //@Override
  public void attach(String controllerName, Integer pin, Double pos) throws Exception {
    attach(controllerName, pin, pos, null);
  }

  //@Override
  public void attach(String controllerName, Integer pin, Double pos, Double speed) throws Exception {
    attach(controllerName, pin, pos, speed, 0.0);
  }
  
  /**
   * maximum complexity attach with "name" of controller - look for errors then
   * call maximum complexity attach with reference to controller
   */
 // @Override
  public void attach(String controllerName, Integer pin, Double pos, Double speed, Double acceleration) throws Exception {
    ServiceInterface si = Runtime.getService(controllerName);
    if (si == null) {
      error("{}.attach({}) cannot find {} in runtime registry", getName(), controllerName, controllerName);
      return;
    }
    if (!ServoController.class.isAssignableFrom(si.getClass())) {
      error("{} is not a ServoController");
      return;
    }
    attach((ServoController) si, pin, pos, speed);
  }

  /**
   * disables and detaches from all controllers
   */
  public void detach() {
    disable();
    for (String controller : controllers) {
      ServiceInterface si = Runtime.getService(controller);
      if (si.isLocal()) {
        ((ServoController) Runtime.getService(controller)).detach(this);
      } else {
        send(controller, "detach", this);
      }
    }
  }

  // @Override
  public void detach(ServoController controller) {
    if (controller == null) {
      return;
    }
    controllers.remove(controller.getName());
  }

  @Override
  public void detach(ServoDataListener service) {
    if (service != null) {
      listeners.remove(service.getName());
    }
  }

  @Override
  public void disable() {
    for (String controller : controllers) {
      ServiceInterface si = Runtime.getService(controller);
      if (si.isLocal()) {
        ((ServoController) Runtime.getService(controller)).servoDisable(this);
      } else {
        send(controller, "servoDisable", this);
      }
    }
    if (enabled) {
      // if changing state then broadcast
      broadcastState();
    }
    enabled = false;
  }

  @Override
  public void enable() {
    for (String controller : controllers) {
      ServiceInterface si = Runtime.getService(controller);
      if (si.isLocal()) {
        ((ServoController) Runtime.getService(controller)).servoEnable(this);
      } else {
        send(controller, "servoEnable", this);
      }
    }
    if (!enabled) {
      // if changing state then broadcast
      broadcastState();
    }
    enabled = true;
  }

  @Override
  public Double getAcceleration() {
    return acceleration;
  }

  @Override
  public Boolean getAutoDisable() {
    return autoDisable;
  }

  @Deprecated /* its a set now */
  @Override
  public String getControllerName() {
    if (controllers.size() > 0) {
      return controllers.iterator().next();
    }
    return null;
  }

  @Override
  public Set<String> getControllers() {
    return controllers;
  }

  @Override
  public Double getMax() {
    return mapper.getMaxX();
  }

  @Override
  public Double getMaxOutput() {
    return mapper.getMaxOutput();
  }

  @Override
  public Double getMin() {
    return mapper.getMinX();
  }

  @Override
  public Double getMinOutput() {
    return mapper.getMinOutput();
  }

  @Override
  public String getPin() {
    return pin;
  }

  /**
   * this value is always only set by an encoder - if available
   */
  @Override
  public Double getPos() {
    return currentPos;
  }

  @Override
  public Double getRest() {
    return rest;
  }

  @Override
  public Double getSpeed() {
    return speed;
  }

  @Override
  public Double getTargetOutput() {
    if (targetPos == null) {
      targetPos = rest;
    }
    targetOutput = mapper.calcOutput(targetPos);
    return targetOutput;
  }

  @Override
  public Boolean isAttached() {
    return (controllers.size() > 0);
  }

  @Override
  public Boolean isEnabled() {
    return enabled;
  }

  @Override
  public Boolean isInverted() {
    return mapper.isInverted();
  }

  @Override
  public void map(Double minX, Double maxX, Double minY, Double maxY) {
    mapper = new Mapper(minX, maxX, minY, maxY);
    broadcastState();
  }

  @Override
  public void moveTo(Double newPos) {
    moveTo(newPos, false, null);
  }

  /**
   * max complexity moveTo
   * 
   * FIXME - move is more general and could be the "max" complexity method with positional information supplied
   * 
   * @param newPos
   * @param isBlocking
   * @param timeout
   */
  public Double moveTo(Double newPos, Boolean isBlocking, Long timeoutMs) {
    // FIXME - implement encoder blocking ...
    // FIXME - when and what should a servo publish and when ?
    // FIXME FIXME FIXME !!!! @*@*!!! - currentPos is the reported position of the servo, targetPos is 
    // the desired position of the servo - currentPos should NEVER be set in this function 
    // even with no hardware encoder a servo can have a TimeEncoder from which position would be guessed - but
    // it would not be "set" here !

    // breakMoveToBlocking=true;
    // synchronized (moveToBlocked) {
    // moveToBlocked.notify(); // Will wake up MoveToBlocked.wait()
    // }
    
    // enableAutoEnable is "always" on - if you want to stop a motor from working use .lock() 
    // which is part of the motor command set ... once you lock a motor you can't do anything until you unlock it

    if (controllers.size() == 0) {
      error(String.format("%s's controller is not set", getName()));
      return currentPos;
    }

    if (newPos < mapper.getMinX()) {
      currentPos = mapper.getMinX();  // FIXME !!! - WRONG !!! pos is never set by a moveTo command !!! unless there is no encoder !
    }
    if (newPos > mapper.getMaxX()) {
      currentPos = mapper.getMaxX();
    }

    targetPos = newPos;

    if (!isEnabled()) {
      // if (newPos != lastPos || !getAutoDisable()) {
      if (newPos != lastPos || !isEnabled()) {      
        enable();
      }
    }

    targetOutput = getTargetOutput();
    lastActivityTime = System.currentTimeMillis();

    if (lastPos != newPos) {
      for (String controller : controllers) {
        ServiceInterface si = Runtime.getService(controller);
        if (si.isLocal()) {
          ((ServoController) Runtime.getService(controller)).servoMoveTo(this);
        } else {
          send(controller, "servoMoveTo", this);          
        }
      }
      currentPos = newPos;
      lastPos = currentPos; // what is the point of this ???
    }

    if (lastPos == newPos) {
      lastPos = targetPos;
      broadcastState(); // publishServo vs publishServoData .. GAH ! :P
    }

    invoke("publishMoveTo", this);
    return currentPos;
  }

  @Override
  public void moveTo(Float newPos) {
    moveTo(newPos.doubleValue());
  }

  @Override
  public void moveTo(Integer newPos) {
    moveTo(newPos.doubleValue());
  }

  @Override
  public Double moveToBlocking(Double pos) {
    return moveTo(pos, true, null);
  }

  @Override
  public Double moveToBlocking(Double newPos, Long timeoutMs) {
    return moveTo(newPos, true, timeoutMs);
  }

  @Override
  public Double moveToBlocking(Float newPos) {
    return moveTo(newPos.doubleValue(), true, null);
  }

  @Override
  public Double moveToBlocking(Float newPos, Long timeoutMs) {
    return moveTo(newPos.doubleValue(), true, timeoutMs);
  }

  @Override
  public Double moveToBlocking(Integer newPos) {
    return moveTo(newPos.doubleValue(), true, null);
  }

  @Override
  public Double moveToBlocking(Integer newPos, Long timeoutMs) {
    return moveTo(newPos.doubleValue(), true, timeoutMs);
  }
  
  /**
   * call back to hand new services registered
   * we want to update our list of possible controllers & encoders
   * @param s
   */
  public void onRegistered(ServiceInterface s) {
    refreshControllers();
    refreshEncoders();
    broadcastState();
  }

  @Override
  public ServoControl publishMoveTo(ServoControl sc) {
    return sc;
  }

  @Override
  public ServoData publishServoData(ServoStatus status, Double pos) {
    ServoData sd = new ServoData();
    sd.name = getName();
    sd.pos = pos;
    sd.speed = speed;
    sd.targetPos = targetPos;
    sd.state = status;
    return sd;
  }

  /**
   * will disable then detach this servo from all controllers
   */
  public void releaseService() {
    super.releaseService();
    detach();
  }

  @Override
  public void rest() {
    targetPos = rest;
    for (String controller : controllers) {
      ServiceInterface si = Runtime.getService(controller);
      if (si.isLocal()) {
        ((ServoController) Runtime.getService(controller)).servoMoveTo(this);
      } else {
        send(controller, "moveTo", this);
      }
    }
  }

  @Override
  public void setAcceleration(Double acceleration) {
    this.acceleration = acceleration;
  }

  @Override
  public void setAutoDisable(Boolean autoDisable) {
    this.autoDisable = autoDisable;
  }

  @Override
  public void setInverted(Boolean invert) {
    mapper.setInverted(invert);
  }

  @Override
  public void setMinMax(Double min, Double max) {
    mapper.setMinMaxInput(min, max);
  }

  @Override
  public void setMinMax(Integer min, Integer max) {
    mapper.setMinMaxInput(min, max);
  }

  @Override
  public void setPin(Integer pin) {
    this.pin = pin + "";
  }

  @Override
  public void setPin(String pin) {
    this.pin = pin;
  }

  @Override
  public void setRest(Double rest) {
    this.rest = rest;
  }

  @Override
  public void setVelocity(Double degreesPerSecond) {
    if (maxSpeed != -1 && degreesPerSecond > maxSpeed) {
      speed = maxSpeed;
      log.info("Trying to set speed to a value greater than max speed");
    }
    this.speed = degreesPerSecond;
    for (String controller : controllers) {
      ServiceInterface si = Runtime.getService(controller);
      if (si.isLocal()) {
        ((ServoController) Runtime.getService(controller)).servoSetVelocity(this);
      } else {
        send(controller, "servoSetVelocity", this);
      }
    }
    broadcastState();
  }

  // FIXME targetPos = pos, reportedSpeed, vs speed - set
  @Override
  public void stop() {
    isSweeping = false;
    sweeper = null;
    for (String controller : controllers) {
      ServiceInterface si = Runtime.getService(controller);
      if (si.isLocal()) {
        ((ServoController) Runtime.getService(controller)).servoSweepStop(this);
      } else {
        send(controller, "servoSweepStop", this);
      }
    }
    broadcastState();
  }

  /**
   * disable servo
   */
  public void stopService() {
    super.stopService();
    disable();
  }

  @Override
  public void sync(ServoControl sc) {
    if (sc == null) {
      log.error("{}.sync(null)", getName());
    }
    syncedServos.add(sc.getName());
  }

  @Override
  public void unsync(ServoControl sc) {
    if (sc == null) {
      log.error("{}.unsync(null)", getName());
    }
    syncedServos.remove(sc.getName());
  }
  
  @Override
  public List<String> refreshControllers() {
    return Runtime.getServiceNamesFromInterface(ServoController.class);
  }
  
  @Override
  public List<String> refreshEncoders() {
    return Runtime.getServiceNamesFromInterface(EncoderControl.class);
  }


  @Override
  public void waitTargetPos() {
    // 
    //while (this.pos != this.targetPos) {
      // Some sleep perhaps?
      // TODO:
    //}

  }

  @Override
  public void setMapper(Mapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Mapper getMapper(Mapper m) {
    return mapper;
  }


}
