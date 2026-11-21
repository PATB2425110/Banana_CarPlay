package ecarx.car;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * ECarX 车辆通信服务的**编译期占位**。
 *
 * ## 为什么工程里要放一份"假的"
 *
 * 车机 ROM 里才有 `ecarx.car.*`，SDK 里没有，Gradle 编译期就找不到这些符号。
 * 但 VIN 读取（{@code CarVinReader}）必须直接调它的接口才拿得到 PA VIN。
 * 于是照 DBtool3.0 的做法：**本工程编译期带一份最小 stub，运行时由 ROM 提供真类**
 * —— 只要运行时的类实现了同样的接口方法，`Stub.asInterface` 与后续调用就走通了。
 *
 * ⚠️ **运行时谁赢**：两个同名类同时存在（本 stub 与 ROM 的）时，ClassLoader 取
 * **首个**可见的。该类是 `ecarx.car`，不在本应用自身的包名空间内，所以一旦 ROM
 * 里存在真实现，本 stub 就只是"编译期占位"，不会被实例化。若 ROM 里**没有**，
 * `CarVinReader` 会在 `Class.forName` 处抛异常并**降级到下一级**，不会崩。
 *
 * ## 契约
 *
 * 只保留 VIN 链路真正用到的两个成员：`getCarService(String)` 与
 * `Stub.asInterface(IBinder)`。方法签名、Binder 事务码（1）、
 * 接口描述符（`"ecarx.car.IECarXCar"`）都必须与真实现一致——
 * **这几处写错就是"一路抛异常、静默降级"，而且看起来像"这台车没 VIN"**。
 *
 * 参考：DBtool3.0 `ecarx-stub/src/main/java/ecarx/car/IECarXCar.java`。
 */
public interface IECarXCar {

    /** 取一条 ECarX 子系统服务（VIN 走 `"car_signal"`）。 */
    IBinder getCarService(String name) throws RemoteException;

    /** `asInterface` 的占位实现：不做跨进程类型校验，直接包一层 Proxy。 */
    final class Stub {
        private Stub() {
        }

        public static IECarXCar asInterface(IBinder binder) {
            return new Proxy(binder);
        }
    }

    final class Proxy implements IECarXCar {
        private final IBinder remote;

        Proxy(IBinder remote) {
            this.remote = remote;
        }

        @Override
        public IBinder getCarService(String name) throws RemoteException {
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken("ecarx.car.IECarXCar");
                data.writeString(name);
                remote.transact(1, data, reply, 0);
                reply.readException();
                return reply.readStrongBinder();
            } finally {
                data.recycle();
                reply.recycle();
            }
        }
    }
}
