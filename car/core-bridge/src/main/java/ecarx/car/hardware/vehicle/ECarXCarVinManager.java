package ecarx.car.hardware.vehicle;

import ecarx.car.hardware.property.ECarXCarPropertyManagerBase;

/**
 * ECarX 车辆 VIN 管理器的**编译期占位**（详见 {@link ecarx.car.IECarXCar} 的说明）。
 *
 * ⚠️ stub 的构造方法**故意不保存** manager：真实现里它靠 manager 去读属性、
 * stub 里没有属性可读。因此 stub 被实例化时 `getPA_VIN_VinNum()` 返回 null，
 * `CarVinReader` 视作未命中并继续降级——**这正是想要的语义**：宁可少一个来源，
 * 也不能凭空造一个 VIN 出来。
 *
 * 参考：DBtool3.0 `ecarx-stub/src/main/java/ecarx/car/hardware/vehicle/ECarXCarVinManager.java`。
 */
public final class ECarXCarVinManager {

    public ECarXCarVinManager(ECarXCarPropertyManagerBase manager) {
    }

    /** PA VIN（车厂写进 PA 分区的车架号）。stub 下恒为 null。 */
    public PATypes.PA_VIN_VinNum getPA_VIN_VinNum() {
        return null;
    }
}
