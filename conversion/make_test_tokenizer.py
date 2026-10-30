"""Train a small byte-level BPE tokenizer with the exact Llama 3 pre-tokenizer settings.

Only for testing the Kotlin tokenizer implementation when the real (gated) tokenizer.json
is not available. CI additionally tests against the real CSM tokenizer.
"""

import argparse

from tokenizers import Regex, Tokenizer, decoders, models, pre_tokenizers, trainers

LLAMA3_PATTERN = (r"(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}{1,3}| ?[^\s\p{L}\p{N}]+[\r\n]*"
                  r"|\s*[\r\n]+|\s+(?!\S)|\s+")

CORPUS = [
    "ދިވެހިރާއްޖެ އަކީ ރީތި ޤައުމެކެވެ. މާލެ އަކީ ވެރިރަށެވެ.",
    "މެލޭޝިއާގައި އިތުރުކުރާ ޓެކްސް، ދިވެހި ދަރިވަރުންނަށް ބުރައަކަށް ނުވާނެ ގޮތެއް ހޯދައިދޭނަން: ހައިދަރު",
    "އައްސަލާމު ޢަލައިކުމް! ކިހިނެއްތަ؟ އަހަރެން ތިބާއަށް އެހީވެދޭނަން.",
    "ރައީސުލްޖުމްހޫރިއްޔާ މިއަދު 2025 ވަނަ އަހަރުގެ ބަޖެޓް ހުށަހެޅުއްވިއެވެ.",
    "The quick brown fox jumps over the lazy dog. It's 12:30 and I'll be there at 1500 hours!",
    "Numbers 1234567 and symbols #@$%^&*() mixed with ދިވެހި text\n\nnew lines\ttabs   spaces.",
] * 20


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--vocab-size", type=int, default=1500)
    args = ap.parse_args()
    tok = Tokenizer(models.BPE(ignore_merges=True))
    tok.pre_tokenizer = pre_tokenizers.Sequence([
        pre_tokenizers.Split(Regex(LLAMA3_PATTERN), behavior="isolated", invert=False),
        pre_tokenizers.ByteLevel(add_prefix_space=False, trim_offsets=True, use_regex=False),
    ])
    tok.decoder = decoders.ByteLevel()
    trainer = trainers.BpeTrainer(vocab_size=args.vocab_size, initial_alphabet=pre_tokenizers.ByteLevel.alphabet(),
                                  special_tokens=["<|begin_of_text|>", "<|end_of_text|>"])
    tok.train_from_iterator(CORPUS, trainer)
    tok.save(args.out)
    print("saved", args.out, tok.get_vocab_size())


if __name__ == "__main__":
    main()
