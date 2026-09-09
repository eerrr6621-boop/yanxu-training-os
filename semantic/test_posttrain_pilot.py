"""Pure training-math tests; no model weights, datasets, network or checkpoints."""
import unittest
import torch
from train_posttrain_pilot import CONFIG,group_loss


class PilotMathTest(unittest.TestCase):
    def test_single_positive_matches_cross_entropy(self):
        scores=torch.tensor([0.2,1.1,-0.4],dtype=torch.float64)
        expected=torch.nn.functional.cross_entropy(scores[None,:],torch.tensor([0]))
        self.assertTrue(torch.allclose(group_loss(scores,1),expected))
    def test_gradient_increases_positive_relative_to_negatives(self):
        scores=torch.tensor([0.2,1.1,-0.4],requires_grad=True)
        group_loss(scores,1).backward()
        self.assertLess(float(scores.grad[0]),0)
        self.assertTrue(bool((scores.grad[1:]>0).all()))
    def test_multiple_positives_share_probability_mass(self):
        scores=torch.tensor([0.3,0.1,-0.5])
        self.assertLess(float(group_loss(scores,2)),float(group_loss(scores,1)))
    def test_four_microbatches_equal_mean_gradient(self):
        examples=[torch.tensor([0.3,0.1,-0.5])+i*.02 for i in range(4)]
        a=torch.tensor(1.,requires_grad=True)
        for x in examples:(group_loss(x*a,1)/4).backward()
        b=torch.tensor(1.,requires_grad=True)
        torch.stack([group_loss(x*b,1) for x in examples]).mean().backward()
        self.assertTrue(torch.allclose(a.grad,b.grad))
    def test_invalid_group_rejected(self):
        for count in (0,3):
            with self.assertRaises(ValueError):group_loss(torch.tensor([1.,2.,3.]),count)
    def test_fixed_number_updates(self):
        self.assertEqual(80*CONFIG["epochs"]//CONFIG["accumulation"],40)
        self.assertEqual(80%CONFIG["accumulation"],0)


if __name__=="__main__":unittest.main()
