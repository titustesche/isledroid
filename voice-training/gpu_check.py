"""Exits 0 only if PyTorch really computes on the AMD GPU (an unsupported chip fails at the first kernel)."""
import sys

import torch

if not torch.cuda.is_available():
    print("   PyTorch sees no GPU (torch.version.hip =", torch.version.hip, ")")
    sys.exit(1)
try:
    a = torch.randn(2048, 2048, device="cuda")
    b = (a @ a).sum().item()
    conv = torch.nn.Conv1d(64, 64, 5).cuda()
    conv(torch.randn(8, 64, 512, device="cuda")).sum().item()
except Exception as e:  # "invalid device function" = this build has no kernels for the chip
    print("   The GPU is visible but kernels fail:", e)
    sys.exit(1)
print("   OK:", torch.cuda.get_device_name(0), "| torch", torch.__version__, "| HIP", torch.version.hip)
