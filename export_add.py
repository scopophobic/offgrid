import torch
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
class AddModel(torch.nn.Module):
    def forward(self, x, y):
        return x + y
model = AddModel().eval()
sample_inputs = (torch.ones(1), torch.ones(1))
et_program = to_edge_transform_and_lower(
    torch.export.export(model, sample_inputs),
    partitioner=[XnnpackPartitioner()]
).to_executorch()
with open("qwen3_1_7b_q4_k_m.pte", "wb") as f:
    f.write(et_program.buffer)
print("Exported qwen3_1_7b_q4_k_m.pte")
