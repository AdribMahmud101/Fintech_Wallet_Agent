# jniLibs

Drop the Rust `cdylib` here, one directory per ABI:

```
jniLibs/arm64-v8a/libfusion_core.so
jniLibs/armeabi-v7a/libfusion_core.so
jniLibs/x86_64/libfusion_core.so
```

Build them from `../fusion-core`:

```bash
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build --release
# then copy jniLibs/<abi>/libfusion_core.so out of fusion-core/target and into the
# matching directory above
```

`.gitkeep` keeps the directory in version control; binaries are not committed.
