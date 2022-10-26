#!/usr/bin/env python3

__author__ = 'BinglanLi'

import os
import re
import sys
from pathlib import Path
from timeit import default_timer as timer

from packaging import version

import vcf_preprocess_utilities as util


# expected tool versions
MIN_BCFTOOLS_VERSION = '1.16'
MIN_BGZIP_VERSION = '1.16'


def run(args):
    """ normalize and prepare the input VCF for PharmCAT """
    start = timer()

    """
    validate arguments
    """
    # validate bcftools
    bcftools_path = args.path_to_bcftools if args.path_to_bcftools else 'bcftools'
    bcftools_version_message = util.execute_subprocess([bcftools_path, '-v'])
    # check the bcftools versions
    bcftools_version_regex = re.search(r'bcftools (\d+(\.\d+)*)', str(bcftools_version_message))
    # warn and quit
    if bcftools_version_regex is not None:
        current_bcftools_version = bcftools_version_regex.group(1)
        if version.parse(current_bcftools_version) < version.parse(MIN_BCFTOOLS_VERSION):
            print("Please use bcftools %s or higher" % MIN_BCFTOOLS_VERSION)
            sys.exit(1)
    else:
        print('Could not find the version information for bcftools')
        print("Please use bcftools %s or higher" % MIN_BCFTOOLS_VERSION)

    # validate bgzip
    bgzip_path = args.path_to_bgzip if args.path_to_bgzip else 'bgzip'
    bgzip_help_message = util.execute_subprocess([bgzip_path, '-h'])
    # check the bgzip version
    bgzip_version_regex = re.search(r'Version: (\d+(\.\d+)*)', str(bgzip_help_message))
    # warn and quit
    if bgzip_version_regex is not None:
        current_bgzip_version = bgzip_version_regex.group(1)
        if version.parse(current_bgzip_version) < version.parse(MIN_BGZIP_VERSION):
            print("Please use bgzip %s or higher" % MIN_BGZIP_VERSION)
            sys.exit(1)
    else:
        print("Could not find the version information for bgzip\n"
              "It is likely you are using a bgzip <= 1.9\n"
              "Please use bgzip %s or higher" % MIN_BGZIP_VERSION)
        sys.exit(1)

    # validate input vcf or file list
    # set up empty variables
    input_vcf = ''
    input_list = ''
    # check whether input is a vcf or a list
    if re.search('[.]vcf([.]b?gz)?$', os.path.basename(args.vcf)):
        input_vcf = args.vcf
    else:
        input_list = args.vcf
    # if single input vcf, validate and bgzip
    if input_vcf:
        if not os.path.exists(input_vcf):
            print("Cannot find", input_vcf)
            sys.exit(1)
        # compress if the input vcf is not bgzipped
        input_vcf = util.bgzipped_vcf(bgzip_path, input_vcf)
    # if a list of vcfs, validate the list file; and bgzip later
    if input_list:
        if not os.path.exists(input_list):
            print("Cannot find", input_list)
            sys.exit(1)
    # get input basename
    input_basename = os.path.basename(os.path.splitext(input_list)[0]) if input_list else util.get_vcf_prefix(input_vcf)
    # validate the reference vcf of PharmCAT PGx positions
    ref_pgx = args.reference_pgx_vcf
    if not os.path.exists(ref_pgx):
        print('Error: VCF of the reference PGx positions was not found at: %s' % ref_pgx)
        sys.exit(1)

    """
    organize the rest of the arguments
    """
    sample_file = args.sample_file
    keep_intermediate_files = args.keep_intermediate_files
    missing_to_ref = args.missing_to_ref
    # define output base name, default to the input base name
    base_filename = args.base_filename if args.base_filename else ''
    # define working directory, default the directory of the first input VCF
    if args.output_dir:
        output_dir = args.output_dir
        # create the output folder
        Path(output_dir).mkdir(parents=True, exist_ok=True)
    elif input_vcf:
        output_dir = os.path.split(os.path.realpath(input_vcf))[0]
    else:
        output_dir = os.path.split(os.path.realpath(input_list))[0]
    print("Saving output to", output_dir)

    # download the human reference sequence if not provided
    if args.reference_genome:
        reference_genome = args.reference_genome
    else:
        if os.path.exists(os.path.join(output_dir, 'reference.fna.bgz')):
            reference_genome = os.path.join(output_dir, 'reference.fna.bgz')
            print("Using default FASTA reference at", reference_genome)
        elif os.path.exists(os.path.join(os.getcwd(), 'reference.fna.bgz')):
            reference_genome = os.path.join(os.getcwd(), 'reference.fna.bgz')
            print("Using default FASTA reference at", reference_genome)
        else:
            reference_genome = util.get_default_grch38_ref_fasta_and_index(output_dir)
            print("Downloaded to %s" % reference_genome)

    # index ref_pgx if not already so
    if not os.path.exists(ref_pgx + '.csi'):
        util.index_vcf(bcftools_path, ref_pgx)

    # read the sample list
    sample_list = []
    if sample_file:
        with open(sample_file, 'r') as file:
            for line in file:
                line = line.strip()
                sample_list.append(line)
        file.close()
    elif input_vcf:
        sample_list = util.obtain_vcf_sample_list(bcftools_path, input_vcf)
    else:
        with open(input_list, 'r') as file:
            for line in file:
                line = line.strip()
                if os.path.isfile(line):
                    sample_list = util.obtain_vcf_sample_list(bcftools_path, line)
                    break
        file.close()

    # check if sample name violates bcftools sample name convention
    if any(',' in sample_name for sample_name in sample_list):
        print('Please remove comma \',\' from sample names, which violates bcftools sample name convention')
        sys.exit(1)

    # list of files to be deleted
    tmp_files_to_be_removed = []

    """
    normalize and prepare vcf for PharmCAT
    """
    # shrink input VCF down to PGx allele defining regions and selected samples
    # modify input VCF chromosomes naming format to <chr##>
    if input_list:
        vcf_pgx_regions = util.extract_regions_from_multiple_files(bcftools_path, bgzip_path, input_list,
                                                                   ref_pgx, output_dir, input_basename, sample_list)
    else:
        vcf_pgx_regions = util.extract_regions_from_single_file(bcftools_path, input_vcf,
                                                                ref_pgx, output_dir, input_basename, sample_list)
    tmp_files_to_be_removed.append(vcf_pgx_regions)

    # normalize the input VCF
    vcf_normalized = util.normalize_vcf(bcftools_path, vcf_pgx_regions, reference_genome, output_dir)
    tmp_files_to_be_removed.append(vcf_normalized)

    # extract the specific PGx genetic variants in the reference PGx VCF
    # this step also generates a report of missing PGx positions in the input VCF
    vcf_normalized_pgx_only = util.filter_pgx_variants(bcftools_path, bgzip_path, vcf_normalized, reference_genome,
                                                       ref_pgx, missing_to_ref, output_dir, input_basename)
    tmp_files_to_be_removed.append(vcf_normalized_pgx_only)

    # output PharmCAT-ready single-sample VCF
    # retain only the PharmCAT allele defining positions in the output VCF file
    util.output_pharmcat_ready_vcf(bcftools_path, vcf_normalized_pgx_only, output_dir, base_filename, sample_list)

    # remove intermediate files
    if not keep_intermediate_files:
        print("Removing intermediate files:")
        for single_path in tmp_files_to_be_removed:
            util.remove_vcf_and_index(single_path)

    end = timer()
    print()
    print("Done.")
    print("Preprocessed input VCF in %.2f seconds" % (end - start))


if __name__ == "__main__":
    import argparse

    # describe the tool
    parser = argparse.ArgumentParser(description='Prepare an input VCF for the PharmCAT')

    # list arguments
    parser.add_argument("-vcf", "--vcf", type=str, required=True,
                        help="Path to a VCF file or a file of paths to VCF files (one file per line), "
                             "sorted by chromosome position.")
    parser.add_argument("-refVcf", "--reference-pgx-vcf", type=str,
                        default=os.path.join(os.getcwd(), "pharmcat_positions.vcf.bgz"),
                        help="A sorted VCF of PharmCAT PGx variants, gzipped with preprocessor scripts. Default = "
                             "\'pharmcat_positions.vcf.bgz\' in the current working directory.")
    parser.add_argument("-refFna", "--reference-genome",
                        help="(Optional) the Human Reference Genome GRCh38/hg38 in the fasta format.")
    parser.add_argument("-S", "--sample-file",
                        help="(Optional) a file of samples to be prepared for the PharmCAT, one sample at a line.")
    parser.add_argument("-bcftools", "--path-to-bcftools",
                        help="(Optional) an alternative path to the executable bcftools.")
    parser.add_argument("-bgzip", "--path-to-bgzip",
                        help="(Optional) an alternative path to the executable bgzip.")
    parser.add_argument("-o", "--output-dir", type=str,
                        help="(Optional) directory for outputs, by default, directory of the first input VCF.")
    parser.add_argument("-bf", "--base-filename", type=str,
                        help="(Optional) output prefix (without file extensions), "
                             "by default the same base name as the input.")
    parser.add_argument("-k", "--keep-intermediate-files", action='store_true',
                        help="(Optional) keep intermediate files, false by default.")
    parser.add_argument("-0", "--missing-to-ref", action='store_true',
                        help="(Optional) assume genotypes at missing PGx sites are 0/0.  DANGEROUS!.")

    # parse arguments
    parsed_args = parser.parse_args()

    # print warnings here
    # alternatively, could use the "warnings" module
    if parsed_args.missing_to_ref:
        print('=============================================================\n'
              'Warning: Argument "-0"/"--missing-to-ref" supplied\n'
              '\n'
              'THIS SHOULD ONLY BE USED IF: you sure your data is reference\n'
              'at the missing positions instead of unreadable/uncallable at\n'
              'those positions.\n'
              '\n'
              'Running PharmCAT with positions as missing vs reference can\n'
              'lead to different results.\n'
              '=============================================================\n')

    # normalize variant representations and reconstruct multi-allelic variants in the input VCF
    run(parsed_args)
