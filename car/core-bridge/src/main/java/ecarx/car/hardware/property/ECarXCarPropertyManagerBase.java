package ecarx.car.hardware.property;

import android.os.Handler;
import android.os.IBinder;

/**
 * ECarX 车辆属性管理器的**编译期占位**（详见 {@link ecarx.car.IECarXCar} 的说明）。
 *
 * 构造签名必须与真实现一致：
 * {@code (IBinder service, Handler handler, boolean register, String tag)}。
 * 运行时若 ROM 有真类，则用真类；没有则本 stub 被实例化但**不做任何事**，
 * 由调用方（`CarVinReader`）读到的空值自然降级到下一级。
 *
 * 参考：DBtool3.0 `ecarx-stub/src/main/java/ecarx/car/hardware/property/ECarXCarPropertyManagerBase.java`。
 */
public class ECarXCarPropertyManagerBase {

    public ECarXCarPropertyManagerBase(IBinder service, Handler handler, boolean register, String tag) {
    }

    /** 断开车辆连接（真实现里会注销回调）；stub 下是空操作。 */
    public void onCarDisconnected() {
    }
}
