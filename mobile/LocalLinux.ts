import { requireNativeModule } from 'expo-modules-core';

export type LocalLinuxStatus = {
  installed: boolean;
  running: boolean;
  directory: string;
  abi: string;
  lastOutput: string;
};

type LocalLinuxNative = {
  getStatus(): Promise<LocalLinuxStatus>;
  installRuntime(url: string, sha256: string): Promise<LocalLinuxStatus>;
  startBot(apiKey: string): Promise<{ running: boolean; pid?: number }>;
  stopBot(): Promise<{ running: boolean }>;
};

export default requireNativeModule<LocalLinuxNative>('LocalLinux');
