package ecarx.car.hardware.vehicle;

/**
 * ECarX 车辆数据类型集合的**编译期占位**（详见 {@link ecarx.car.IECarXCar} 的说明）。
 *
 * 只保留 VIN 链路用到的 {@link PA_VIN_VinNum}。真实现里它是包装类，
 * `getData()` 返回字符串（可能带空格/换行），调用方负责 trim 与合法性校验。
 *
 * 参考：DBtool3.0 `ecarx-stub/src/main/java/ecarx/car/hardware/vehicle/PATypes.java`。
 */
public final class PATypes {

    private PATypes() {
    }

    /** PA_VIN（车架号）包装。 */
    public static final class PA_VIN_VinNum {
        /** stub 返回空串：真值只在 ROM 的真实现里。 */
        public String getData() {
            return "";
        }
    }
}
